package com.fromwau.kern.logger

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.Sink
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.time.Clock

private const val INTERNAL_TAG = "kern.logger"

/**
 * An entry logged before [configure] is dropped once this many are waiting, oldest first. A caller that
 * never configures the logger otherwise buffers for the life of the process.
 */
private const val MAX_BUFFERED = 1024

/**
 * A logger you reconfigure while it runs, and that holds on to what you logged before it knew how.
 *
 * Every application logs before it has read its config, which is exactly when the log level is still
 * unknown. Until [configure] is called those entries are buffered rather than filtered, so a `DEBUG` line
 * from early startup survives to be printed once the config turns `DEBUG` on. After that, [state] is the
 * live answer to every question about where and how a line is written, and changing it takes effect on
 * the next entry.
 *
 * ```kotlin
 * Log.tag("Startup").i { "reading config" }          // buffered: no level known yet
 *
 * Log.configure(LoggerRuntimeState(level = LogLevel.DEBUG, file = Path(logDir, "app.log")))
 * // the buffered line is replayed, and everything after it is written live
 *
 * Log.update { it.copy(level = LogLevel.VERBOSE) }   // a settings screen, applied immediately
 * ```
 *
 * Safe to log to from any thread. Use [Log] unless you need more than one logger, or need [sinks].
 *
 * @param sinks extra destinations beyond the console and the file. See [LogSink].
 */
public class Logger(
    private val sinks: List<LogSink> = emptyList(),
) {
    private val lock = reentrantLock()
    private val mutableState = MutableStateFlow<LoggerRuntimeState?>(null)
    private val buffered = ArrayDeque<LogEntry>()

    private var openPath: Path? = null
    private var openSink: Sink? = null

    /**
     * How the logger is behaving right now, or null before the first [configure] while entries are still
     * being buffered.
     *
     * Read it to show the current level in a settings screen, or collect it to react to a change. Writing
     * goes through [configure] and [update] only, so the state cannot drift from a second authority.
     */
    public val state: StateFlow<LoggerRuntimeState?> = mutableState.asStateFlow()

    /**
     * Replaces the runtime state, and on the first call writes out everything buffered so far.
     *
     * Replayed entries keep the timestamp they were logged at, and are filtered by the level you are
     * configuring now: that is the point of buffering, since it is the config that decides what was worth
     * keeping. Calling this again later is not a re-initialization, just a new state.
     */
    public fun configure(runtime: LoggerRuntimeState) {
        lock.withLock {
            applyState(runtime)
            if (buffered.isEmpty()) return@withLock

            val replay = buffered.toList()
            buffered.clear()
            replay.forEach { dispatch(it, runtime) }
        }
    }

    /**
     * Edits the current runtime state in place, atomically, for a change that depends on what is already
     * set: `update { it.copy(level = LogLevel.DEBUG) }`.
     *
     * Does nothing before the first [configure], since there is no state to transform yet.
     */
    public fun update(transform: (LoggerRuntimeState) -> LoggerRuntimeState) {
        lock.withLock {
            val current = mutableState.value ?: return@withLock
            applyState(transform(current))
        }
    }

    /** Names the source of the entries you are about to log: `logger.tag("Scanner").i { "done" }`. */
    public fun tag(tag: String): TaggedLogger = TaggedLogger(tag, this)

    /**
     * Logs one entry, if [level] passes the configured threshold or nothing is configured yet.
     *
     * Prefer [tag], which reads better at a call site and fills in the tag for you.
     *
     * @param message built only once the entry is known to be worth keeping, so an expensive block costs
     *   nothing while it is filtered out.
     */
    public fun log(
        tag: String,
        level: LogLevel,
        throwable: Throwable? = null,
        fields: Map<String, String> = emptyMap(),
        message: () -> String,
    ) {
        // Filtered before the lock so a suppressed entry never blocks and never builds its message. The
        // state may change before the write below, which is why dispatch decides again.
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
                else -> dispatch(entry, current)
            }
        }
    }

    /** Closes the log file. Logging afterwards reopens it, so this ends a run rather than the logger. */
    public fun close() {
        lock.withLock { closeFile() }
    }

    private fun applyState(runtime: LoggerRuntimeState) {
        if (openPath != runtime.file) closeFile()
        mutableState.value = runtime
    }

    private fun buffer(entry: LogEntry) {
        if (buffered.size >= MAX_BUFFERED) buffered.removeFirst()
        buffered.addLast(entry)
    }

    private fun dispatch(entry: LogEntry, runtime: LoggerRuntimeState) {
        if (!runtime.passes(entry.level)) return

        val line = entry.render(runtime.format)
        if (runtime.console) consoleWriter.write(entry, line, runtime)
        runtime.file?.let { appendToFile(line, it, runtime) }

        sinks.forEach { sink ->
            try {
                sink.write(entry, runtime)
            } catch (e: Exception) {
                report("a sink", e, runtime)
            }
        }
    }

    private fun appendToFile(line: String, file: Path, runtime: LoggerRuntimeState) {
        try {
            val sink = sinkFor(file)
            sink.writeString(line)
            sink.writeString("\n")
            sink.flush()
        } catch (e: Exception) {
            // Drop the handle so the next entry reopens: a full disk or a deleted directory can recover.
            closeFile()
            report("the log file", e, runtime)
        }
    }

    private fun sinkFor(file: Path): Sink {
        openSink?.let { if (openPath == file) return it }

        closeFile()
        file.parent?.let { SystemFileSystem.createDirectories(it, mustCreate = false) }

        return SystemFileSystem.sink(file, append = true).buffered().also {
            openSink = it
            openPath = file
        }
    }

    private fun closeFile() {
        try {
            openSink?.close()
        } catch (_: Exception) {
            // Already unusable, and there is nowhere left to report it that is not the thing that failed.
        }
        openSink = null
        openPath = null
    }

    private fun report(what: String, cause: Throwable, runtime: LoggerRuntimeState) {
        val entry = LogEntry(
            timestamp = Clock.System.now(),
            tag = INTERNAL_TAG,
            level = LogLevel.ERROR,
            message = "$what failed: ${cause.message ?: cause::class.simpleName}",
        )

        // Console directly, never back through dispatch, which would re-enter the destination that failed.
        consoleWriter.write(entry, entry.toTextLine(), runtime)
    }
}

/**
 * The process-wide logger, for the common case where one is enough.
 *
 * It carries no [LogSink]s; construct your own [Logger] if you need them.
 */
public val Log: Logger = Logger()

private fun LoggerRuntimeState.passes(candidate: LogLevel): Boolean =
    candidate.severity >= level.severity
