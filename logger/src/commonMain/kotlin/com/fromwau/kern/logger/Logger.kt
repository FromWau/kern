package com.fromwau.kern.logger

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Clock

private const val INTERNAL_TAG = "kern.logger"

/** How many entries [Logger.holdUntilConfigured] holds; logging another before [Logger.configure] throws. */
private const val MAX_BUFFERED = 1024

/**
 * A logger you reconfigure while it runs. It logs from the start with the default [LoggerConfig]: `INFO` and
 * above, as text, to the console. [configure] changes that, and a change takes effect on the next entry.
 *
 * ```kotlin
 * Log.tag("Scanner").i { "scan complete" }           // written at once, with the defaults
 *
 * Log.configure(LoggerConfig(level = LogLevel.DEBUG, file = Path(logDir, "app.log")))
 * Log.configure { it.copy(level = LogLevel.VERBOSE) }   // a settings screen, applied at once
 * ```
 *
 * An app that logs before it has read its own config can ask for those entries to be held instead, with
 * [holdUntilConfigured], so the config decides what they were worth.
 *
 * Safe to log to from any thread. Use [Log] unless you need more than one logger, or need extra sinks.
 */
public class Logger internal constructor(
    private val sinks: List<LogSink>,
    private val console: ConsoleWriter,
) {
    /** @param sinks extra destinations beyond the console and the file. See [LogSink]. */
    public constructor(sinks: List<LogSink> = emptyList()) : this(sinks, consoleWriter)

    private val lock = reentrantLock()
    private val mutableState = MutableStateFlow<LoggerConfig?>(LoggerConfig())
    private val buffered = ArrayDeque<LogEntry>()

    // Looked up once per configure: on native the lookup reads the system's zone data and costs far more than
    // rendering the line.
    private var localClock = systemLocalClock()

    // Set by a failed append, which may have left part of a line behind for the next one to end.
    private var lastAppendFailed = false

    private var openPath: Path? = null
    private var openFile: AppendFile? = null

    /**
     * How the logger is behaving right now, or null while [holdUntilConfigured] has it holding entries.
     *
     * Read it to show the current level in a settings screen, or collect it to react to a change. Writing
     * goes through [configure] and [holdUntilConfigured] only, so the state cannot drift from a
     * second authority.
     */
    public val state: StateFlow<LoggerConfig?> = mutableState.asStateFlow()

    /**
     * Replaces the configuration, and writes out every entry [holdUntilConfigured] was holding.
     *
     * Replayed entries keep the timestamp they were logged at, and are filtered by the level you are
     * configuring now, since it is the config that decides what was worth keeping. Calling this again later is
     * not a re-initialization, just a new configuration.
     */
    public fun configure(config: LoggerConfig) {
        lock.withLock {
            applyConfig(config)
            if (buffered.isEmpty()) return@withLock

            val held = buffered.toList()
            buffered.clear()
            deliver(held, config)
        }
    }

    /**
     * Changes the configuration from what it is now: `configure { it.copy(level = LogLevel.DEBUG) }`. Reading
     * and replacing happen under one lock, so a settings screen and a config reload on another thread cannot
     * undo each other's change, as reading [state] and calling `configure(config)` could.
     *
     * While [holdUntilConfigured] holds entries there is no configuration yet, so [transform] receives the
     * default [LoggerConfig], and the result ends the hold: the held entries are written with it, as
     * `configure(config)` would. A watchdog that should wait for the real config must not call this first.
     *
     * [transform] runs under the logger's lock, so every thread that logs waits for it. Keep it to a `copy`:
     * work out the new values beforehand, and never call [configure] or wait on a thread that logs from inside
     * it. A [configure] made inside [transform] is replaced by the value [transform] returns.
     */
    public fun configure(transform: (LoggerConfig) -> LoggerConfig) {
        lock.withLock {
            configure(transform(mutableState.value ?: LoggerConfig()))
        }
    }

    /**
     * Holds every entry from now on until the next [configure], instead of writing it, so an app that logs
     * before it has read its own config lets that config decide what the early entries were worth.
     *
     * ```kotlin
     * fun main() {
     *     Log.holdUntilConfigured()
     *     Log.tag("Startup").d { "looking for a config file" }   // held
     *     val config = readConfig()
     *     Log.configure(LoggerConfig(level = config.logLevel))   // replayed, if DEBUG is on
     * }
     * ```
     *
     * Call it before logging anything, since what was logged earlier has already been written with the
     * config of that moment. A held entry builds its message when it is logged, since no level has decided
     * against it yet. [close] before any [configure] writes the held entries with the default config, so none
     * is lost.
     *
     * At most 1024 entries are held. Every entry past that throws an [IllegalStateException], on whichever
     * thread logs it: a program that logs that much before configuring has forgotten to call [configure], or
     * does not need to hold at all.
     */
    public fun holdUntilConfigured() {
        lock.withLock {
            closeFile()
            mutableState.value = null
        }
    }

    /** Names the source of the entries you are about to log: `logger.tag("Scanner").i { "done" }`. */
    public fun tag(tag: String): TaggedLogger = TaggedLogger(tag, this)

    /**
     * Logs one entry, if [level] passes the configured threshold, or holds it while [holdUntilConfigured] is
     * in effect.
     *
     * Prefer [tag], which reads better at a call site and fills in the tag for you.
     *
     * @param message built only once the entry is known to be worth keeping, so an expensive block costs
     *   nothing while it is filtered out. While [holdUntilConfigured] holds entries it is built at once.
     */
    public fun log(
        tag: String,
        level: LogLevel,
        throwable: Throwable? = null,
        fields: Map<String, String> = emptyMap(),
        message: () -> String,
    ) {
        // Filtered before the lock so a suppressed entry never blocks and never builds its message. The
        // state may change before the write below, which is why deliver decides again.
        val snapshot = mutableState.value
        if (snapshot != null && !snapshot.passes(level)) return

        val entry = LogEntry(
            timestamp = Clock.System.now(),
            tag = tag,
            level = level,
            message = message(),
            fields = fields,
            throwable = throwable,
        )

        lock.withLock {
            when (val current = mutableState.value) {
                null -> buffer(entry)
                else -> deliver(listOf(entry), current)
            }
        }
    }

    /**
     * Closes the log file. Logging afterwards reopens it, so this ends a run rather than the logger, and after a
     * tool such as logrotate has moved the file it is how the next entry lands in a new file at the path.
     *
     * Called while [holdUntilConfigured] is still holding, it first writes the held entries with the default
     * [LoggerConfig], followed by a warning that [configure] never came, so nothing logged is lost. With nothing
     * held there is nothing to warn about, and the hold simply ends.
     */
    public fun close() {
        lock.withLock {
            if (mutableState.value == null) {
                val defaults = LoggerConfig()
                applyConfig(defaults)
                if (buffered.isNotEmpty()) {
                    val warning = LogEntry(
                        timestamp = Clock.System.now(),
                        tag = INTERNAL_TAG,
                        level = LogLevel.WARN,
                        message = "close() was called before configure(), " +
                            "so the held entries were written with the default config",
                    )
                    val held = buffered.toList() + warning
                    buffered.clear()
                    deliver(held, defaults)
                }
            }
            closeFile()
        }
    }

    private fun applyConfig(config: LoggerConfig) {
        if (openPath != config.file) closeFile()
        localClock = systemLocalClock()
        mutableState.value = config
    }

    private fun buffer(entry: LogEntry) {
        // The one throw in kern: holding this much unconfigured is a program that forgot a call, and dropping
        // entries silently would hide it.
        check(buffered.size < MAX_BUFFERED) {
            "Logger held $MAX_BUFFERED entries without being configured: did you forget to call configure(), " +
                "or should holdUntilConfigured() be removed?"
        }
        buffered.addLast(entry)
    }

    /**
     * Writes every entry that passes the level to the file and the sinks, then to the console. The console goes
     * last: natively a reader that closed the pipe ends the process at the console write, and by then every entry
     * is already in the file and the sinks.
     */
    private fun deliver(entries: List<LogEntry>, config: LoggerConfig) {
        val lines = entries
            .filter { config.passes(it.level) }
            .map { it to it.render(config.format, localClock) }

        lines.forEach { (entry, line) -> persist(entry, line, config) }
        if (config.console != Console.OFF) lines.forEach { (entry, line) -> console.write(entry, line, config) }
    }

    // A sink is the caller's code, so what it throws reaches the caller, like a message builder that throws.
    private fun persist(entry: LogEntry, line: String, config: LoggerConfig) {
        config.file?.let { appendToFile(line, it) }
        sinks.forEach { it.write(entry, config) }
    }

    // A write that fails is dropped: the file is the caller's environment, and nothing is left to tell. The
    // handle goes too, so the next entry reopens and a full disk or a missing directory recovers once fixed.
    private fun appendToFile(line: String, file: Path) {
        try {
            val target = fileFor(file)
            val text = if (lastAppendFailed && !endsWithNewline(file)) "\n$line\n" else "$line\n"
            target.append(text.encodeToByteArray())
            lastAppendFailed = false
        } catch (_: Exception) {
            lastAppendFailed = true
            closeFile()
        }
    }

    // The torn line stays as it is, since truncating could cut another process's entries; ending it keeps the
    // next entry readable on a line of its own.
    private fun endsWithNewline(file: Path): Boolean {
        val size = SystemFileSystem.metadataOrNull(file)?.size ?: return true
        if (size == 0L) return true

        return SystemFileSystem.source(file).buffered().use { source ->
            source.skip(size - 1)
            source.readByte() == '\n'.code.toByte()
        }
    }

    private fun fileFor(file: Path): AppendFile {
        openFile?.let { if (openPath == file) return it }

        closeFile()
        file.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }

        return openAppendFile(file).also {
            openFile = it
            openPath = file
        }
    }

    private fun closeFile() {
        try {
            openFile?.close()
        } catch (_: Exception) {
            // Already unusable; the next entry opens a fresh handle.
        }
        openFile = null
        openPath = null
    }
}

/**
 * The process-wide logger, for the common case where one is enough.
 *
 * It carries no [LogSink]s; construct your own [Logger] if you need them.
 */
public val Log: Logger = Logger()

private fun LoggerConfig.passes(candidate: LogLevel): Boolean =
    candidate.severity >= level.severity
