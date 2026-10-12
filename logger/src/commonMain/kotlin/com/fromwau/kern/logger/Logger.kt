package com.fromwau.kern.logger

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Clock
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** How many entries [Logger.holdUntilConfigured] holds; past that the oldest give way. */
private const val MAX_HELD = 1024

/**
 * A logger you reconfigure while it runs. It logs from the start with the default [LoggerConfig]: `INFO` and
 * above, as text, to the platform's console. [configure] changes that, and a change takes effect on the next
 * entry.
 *
 * ```kotlin
 * Log.tag("Scanner").i { "scan complete" }           // written at once, with the defaults
 *
 * Log.configure(LoggerConfig(level = LogLevel.DEBUG, file = FileOutput.Append(Path(logDir, "app.log"))))
 * Log.configure { it.copy(level = LogLevel.VERBOSE) }   // a settings screen, applied at once
 * ```
 *
 * An app that logs before it has read its own config can ask for those entries to be held instead, with
 * [holdUntilConfigured], so the config decides what they were worth.
 *
 * A destination that fails never takes the others down with it, and by default never reaches the code that
 * logged: [LoggerConfig.file] and [LoggerConfig.sinkFailure] decide what happens to a refused entry, and
 * [health] counts every one.
 *
 * Safe to log to from any thread. Use [Log] unless you need more than one logger, or need extra sinks.
 */
public class Logger internal constructor(
    sinks: List<LogSink>,
    private val console: ConsoleWriter,
    private val delivery: LogDelivery = LogDelivery.Inline,
    private val report: (String) -> Unit = ::reportToStandardError,
    private val timeSource: TimeSource = TimeSource.Monotonic,
) {
    /**
     * @param sinks extra destinations beyond the console and the file. See [LogSink].
     * @param delivery which thread writes each entry; the thread that logged, unless you hand over another.
     */
    public constructor(
        sinks: List<LogSink> = emptyList(),
        delivery: LogDelivery = LogDelivery.Inline,
    ) : this(sinks, consoleWriter, delivery)

    private val lock = reentrantLock()
    private val mutableState = MutableStateFlow<LoggerConfig?>(LoggerConfig())
    private val slots = sinks.map(::SinkSlot)
    private val file = LogFile(timeSource)

    private val held = ArrayDeque<LogEntry>()
    private var droppedFromThisHold = 0L
    private var droppedFromHolds = 0L

    // Reported once per source, so a dead disk costs one line on standard error rather than one per entry.
    private var fileFailureReported = false
    private var unreachableThrowReported = false

    private var healthChanged = false
    private val mutableHealth = MutableStateFlow(LoggerHealth(sinks = slots.map { it.health }))

    // Looked up once per configure: on native the lookup reads the system's zone data and costs far more than
    // rendering the line.
    private var localClock = systemLocalClock()

    /**
     * How the logger is behaving right now, or null while [holdUntilConfigured] has it holding entries.
     *
     * Read it to show the current level in a settings screen, or collect it to react to a change. Writing
     * goes through [configure] and [holdUntilConfigured] only, so the state cannot drift from a
     * second authority.
     */
    public val state: StateFlow<LoggerConfig?> = mutableState.asStateFlow()

    /**
     * What the file and the sinks have failed to take, and what a hold has dropped. It changes only when something
     * fails, recovers or is dropped, so collecting it costs nothing while all is well.
     */
    public val health: StateFlow<LoggerHealth> = mutableHealth.asStateFlow()

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
            val replay = takeHeld()
            if (replay.isNotEmpty()) deliver(replay, config)
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
     * At most 1024 entries are held. Past that the oldest give way, and the replay starts with a warning saying
     * how many, also counted in [LoggerHealth.heldDropped].
     *
     * Waits first for every entry already handed to the [LogDelivery], so call it from an ordinary thread, never
     * from inside a [LogSink].
     */
    public fun holdUntilConfigured() {
        delivery.flush()
        lock.withLock {
            closeFile(mutableState.value)
            mutableState.value = null
            droppedFromThisHold = 0
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
     * @param fields structured values; see [TaggedLogger] for how each type is written.
     * @param message built only once the entry is known to be worth keeping, so an expensive block costs
     *   nothing while it is filtered out. While [holdUntilConfigured] holds entries it is built at once.
     * @throws Exception only what a destination set to [FileFailure.Throw] or [SinkFailure.Throw] failed with,
     *   after every other destination has the entry, and only with [LogDelivery.Inline].
     */
    public fun log(
        tag: String,
        level: LogLevel,
        throwable: Throwable? = null,
        fields: Map<String, Any?> = emptyMap(),
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

        // An entry a sink logs while it is being written to goes through at once, on the thread already holding
        // the lock: queued instead, it would reach that sink later, with nothing left to say where it came from.
        if (lock.tryLock()) {
            try {
                if (slots.any { it.busy }) return holdOrDeliver(entry)
            } finally {
                lock.unlock()
            }
        }
        delivery.submit {
            try {
                lock.withLock { holdOrDeliver(entry) }
            } catch (e: Exception) {
                if (delivery !== LogDelivery.Inline) reportUnreachableThrow(e)
                throw e
            }
        }
    }

    private fun reportUnreachableThrow(failure: Exception) {
        if (unreachableThrowReported) return
        unreachableThrowReported = true
        reportFailure {
            "a write set to FileFailure.Throw or SinkFailure.Throw failed ($failure), but the delivery writes " +
                "off the calling thread, so it cannot reach the code that logged"
        }
    }

    private fun holdOrDeliver(entry: LogEntry) {
        when (val current = mutableState.value) {
            null -> hold(entry)
            else -> deliver(listOf(entry), current)
        }
    }

    /**
     * Closes the log file, after one last attempt at the entries it refused, and makes the same last attempt for
     * every [RetryingSink]; what is still refused is reported in one line per destination on standard error.
     * Logging afterwards reopens the file, so this ends a run rather than the logger, and after a tool such as
     * logrotate has moved the file it is how the next entry lands in a new file at the path.
     *
     * Called while [holdUntilConfigured] is still holding, it first writes the held entries with the default
     * [LoggerConfig], followed by a warning that [configure] never came, so nothing logged is lost. With nothing
     * held there is nothing to warn about, and the hold simply ends.
     *
     * Waits first for every entry already handed to the [LogDelivery], so call it from an ordinary thread, never
     * from inside a [LogSink].
     */
    public fun close() {
        delivery.flush()
        lock.withLock {
            if (mutableState.value == null) {
                val defaults = LoggerConfig()
                applyConfig(defaults)
                val replay = takeHeld()
                if (replay.isNotEmpty()) {
                    val warning = internalEntry(
                        LogLevel.WARN,
                        "close() was called before configure(), " +
                            "so the held entries were written with the default config",
                    )
                    deliver(replay + warning, defaults)
                }
            }
            closeFile(mutableState.value)
            closeRetryingSinks()
        }
    }

    private fun closeRetryingSinks() {
        slots.forEachIndexed { index, slot ->
            val (count, cause) = slot.sink.retrying()?.flushOrAbandon() ?: return@forEachIndexed
            reportFailure { "sink $index: ${entries(count)} could not be delivered: $cause" }
        }
    }

    private fun applyConfig(config: LoggerConfig) {
        val nextPath = (config.file as? FileOutput.Append)?.path
        if (file.path != null && file.path != nextPath) closeFile(mutableState.value)
        localClock = systemLocalClock()
        mutableState.value = config
    }

    private fun hold(entry: LogEntry) {
        if (held.size >= MAX_HELD) {
            held.removeFirst()
            droppedFromThisHold++
            droppedFromHolds++
            healthChanged = true
            publishHealth()
        }
        held.addLast(entry)
    }

    /** The held entries for a replay, led by a warning when some gave way, and the hold emptied. */
    private fun takeHeld(): List<LogEntry> {
        val dropped = droppedFromThisHold
        val replay = buildList {
            if (dropped > 0) {
                add(
                    internalEntry(
                        LogLevel.WARN,
                        "dropped the $dropped oldest held entries: more than $MAX_HELD were logged before configure()",
                    ),
                )
            }
            addAll(held)
        }
        held.clear()
        droppedFromThisHold = 0
        return replay
    }

    /**
     * Writes every entry that passes the level to the file and the sinks, then to the console. The console goes
     * last: natively a reader that closed the pipe ends the process at the console write, and by then every entry
     * is already in the file and the sinks.
     */
    private fun deliver(entries: List<LogEntry>, config: LoggerConfig) {
        val fileFormat = (config.file as? FileOutput.Append)?.format
        val consoleFormat = config.console.format
        val lines = entries
            .filter { config.passes(it.level) }
            .map { entry ->
                val fileLine = fileFormat?.let { entry.render(it, localClock) }
                val consoleLine = when (consoleFormat) {
                    null -> null
                    fileFormat -> fileLine
                    else -> entry.render(consoleFormat, localClock)
                }
                Rendered(entry, fileLine, consoleLine)
            }

        var thrown: Exception? = null
        fun keep(failure: Exception?) {
            if (failure == null) return
            val first = thrown
            if (first == null) thrown = failure else first.addSuppressed(failure)
        }

        lines.forEach { (entry, fileLine, _) ->
            if (fileLine != null) keep(writeFile(entry, fileLine, config))
            slots.forEachIndexed { index, slot -> keep(writeSink(index, slot, entry, config)) }
        }
        lines.forEach { (entry, _, consoleLine) -> if (consoleLine != null) console.write(entry, consoleLine, config) }

        publishHealth()
        thrown?.let { throw it }
    }

    /** @return the failure to throw under [FileFailure.Throw], or null. */
    private fun writeFile(entry: LogEntry, line: String, config: LoggerConfig): Exception? {
        val output = config.file as? FileOutput.Append ?: return null
        val wasIdle = file.idle
        val failure = file.write(FileLine(entry.timestamp, line), output) { gapLine(it, config) }
        if (!wasIdle || !file.idle) healthChanged = true
        if (failure == null) return null

        val fate = when (output.onFailure) {
            FileFailure.Throw -> return failure
            is FileFailure.Retry -> "held for a retry"
            FileFailure.Drop -> "dropped"
        }
        if (!fileFailureReported) {
            fileFailureReported = true
            reportFailure { "the log file ${output.path} refused a write ($failure); its entries are $fate" }
        }
        return null
    }

    /** @return the failure to throw under [SinkFailure.Throw], or null. */
    private fun writeSink(index: Int, slot: SinkSlot, entry: LogEntry, config: LoggerConfig): Exception? {
        // An entry the sink logged while handling one would come straight back to it, and a failing sink that
        // logs its own failure would loop.
        if (slot.busy || entry.level.severity < slot.sink.minLevel.severity) return null

        val policy = config.sinkFailure
        if (policy is SinkFailure.Drop && policy.breaker is Breaker.Trip && slot.isOpen()) {
            slot.dropped++
            healthChanged = true
            return null
        }

        slot.busy = true
        try {
            slot.sink.write(entry, config)
            if (slot.consecutiveFailures > 0) {
                slot.consecutiveFailures = 0
                slot.openUntil = null
                healthChanged = true
            }
            return null
        } catch (e: Exception) {
            // A CancellationException included: write is not suspending, so it is the sink's own, such as a
            // timeout inside runBlocking, never the cancellation of the code that logged.
            slot.failures++
            slot.dropped++
            slot.lastError = e
            slot.consecutiveFailures++
            healthChanged = true

            return when (policy) {
                SinkFailure.Throw -> e
                is SinkFailure.Drop -> {
                    val breaker = policy.breaker
                    if (breaker is Breaker.Trip && slot.consecutiveFailures >= breaker.consecutiveFailures) {
                        slot.openUntil = timeSource.markNow() + breaker.cooldown
                    }
                    if (!slot.failureReported) {
                        slot.failureReported = true
                        val name = slot.sink::class.simpleName
                        reportFailure { "sink $index ($name) threw $e; its entries are dropped" }
                    }
                    null
                }
            }
        } finally {
            slot.busy = false
        }
    }

    private fun closeFile(config: LoggerConfig?) {
        val path = file.path ?: return
        val drop = ((config?.file as? FileOutput.Append)?.onFailure as? FileFailure.Retry)?.drop ?: DropPolicy.OLDEST
        val wasIdle = file.idle
        val lost = file.close(drop) { gapLine(it, config ?: LoggerConfig()) }
        if (lost != null) {
            val (count, cause) = lost
            reportFailure { "${entries(count)} for $path could not be written: $cause" }
        }
        fileFailureReported = false
        if (!wasIdle) healthChanged = true
        publishHealth()
    }

    private fun gapLine(gap: Gap, config: LoggerConfig): String {
        val format = (config.file as? FileOutput.Append)?.format ?: LogFormat.TEXT
        return gapEntry(gap, "the log file").render(format, localClock)
    }

    // A failure while reporting a failure is swallowed: there is nowhere left to report it.
    private fun reportFailure(message: () -> String) {
        try {
            report("$INTERNAL_TAG: ${message()}")
        } catch (_: Exception) {
        }
    }

    private fun publishHealth() {
        if (!healthChanged) return
        healthChanged = false
        mutableHealth.value = LoggerHealth(file.health, slots.map { it.health }, droppedFromHolds)
    }

    private data class Rendered(val entry: LogEntry, val fileLine: String?, val consoleLine: String?)

    private class SinkSlot(val sink: LogSink) {
        var failures = 0L
        var dropped = 0L
        var lastError: Throwable? = null
        var consecutiveFailures = 0
        var openUntil: TimeMark? = null
        var failureReported = false
        var busy = false

        fun isOpen(): Boolean = openUntil?.hasNotPassedNow() ?: false

        val health: SinkHealth
            get() = SinkHealth(failures, dropped, lastError, breakerOpen = openUntil != null)
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
