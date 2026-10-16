package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Records every entry it is handed. [failWith] decides per attempt whether the write fails instead; [onWrite] runs
 * first, on the sink's worker.
 */
internal class Recorder(
    override val name: String = "recorder",
    override val onFailure: SinkFailure = SinkFailure.Drop(),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
    private val failWith: (attempt: Int, entry: LogEntry) -> SinkError? = { _, _ -> null },
    private val onWrite: (LogEntry) -> Unit = {},
) : LogSink {
    private val lock = reentrantLock()
    private val recorded = mutableListOf<LogEntry>()
    private var attemptCount = 0
    private var closeCount = 0
    private var writesAfterCloseCount = 0

    val entries: List<LogEntry>
        get() = lock.withLock { recorded.toList() }

    val messages: List<String>
        get() = entries.map { it.message }

    val attempts: Int
        get() = lock.withLock { attemptCount }

    val closes: Int
        get() = lock.withLock { closeCount }

    /** Writes the sink was handed after its first close. */
    val writesAfterClose: Int
        get() = lock.withLock { writesAfterCloseCount }

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
        val attempt = lock.withLock {
            if (closeCount > 0) writesAfterCloseCount++
            ++attemptCount
        }
        onWrite(entry)
        failWith(attempt, entry)?.let { return Err(it) }
        lock.withLock { recorded += entry }
        return Ok(Unit)
    }

    override fun close(): EmptyResult<SinkError> {
        lock.withLock { closeCount++ }
        return Ok(Unit)
    }
}

internal val TestSink.messages: List<String>
    get() = received.map { it.message }

/**
 * Records every batch it is handed whole. [failWith] decides per call whether the batch fails instead; [onBatch] runs
 * first, on the sink's worker.
 */
@OptIn(InternalKernApi::class)
internal class BatchRecorder(
    override val name: String = "batches",
    override val onFailure: SinkFailure = SinkFailure.Drop(),
    override val capacity: Int = 10_000,
    private val failWith: (call: Int, batch: List<LogEntry>) -> SinkError? = { _, _ -> null },
    private val onBatch: (List<LogEntry>) -> Unit = {},
) : BatchSink {
    private val lock = reentrantLock()
    private val recorded = mutableListOf<List<LogEntry>>()
    private var callCount = 0

    /** Every batch written, in order, the gap entries included. */
    val batches: List<List<LogEntry>>
        get() = lock.withLock { recorded.toList() }

    val calls: Int
        get() = lock.withLock { callCount }

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = writeBatch(listOf(entry), config)

    override fun writeBatch(entries: List<LogEntry>, config: SinkConfig): EmptyResult<SinkError> {
        val call = lock.withLock { ++callCount }
        onBatch(entries)
        failWith(call, entries)?.let { return Err(it) }
        lock.withLock { recorded += entries.toList() }
        return Ok(Unit)
    }
}

/**
 * A logger with no sink, reporting its own failures into [reports] rather than standard error. A removed sink that
 * keeps failing gives up after [retireTimeout], so one left retrying cannot keep a Node.js test run alive for long.
 */
internal fun testLogger(
    reports: MutableList<String> = mutableListOf(),
    retireTimeout: Duration = 500.milliseconds,
): Logger = Logger().also { logger ->
    // Sinks fail on their own workers, so two reports can arrive at once.
    val lock = reentrantLock()
    logger.report = { lock.withLock { reports += it } }
    logger.retireTimeout = retireTimeout
    logger.removeAll()
}

/** Removes every sink; each drains and closes on its own worker. */
internal fun Logger.removeAll() {
    val _ = configure { LoggerConfig(emptyList()) }
}

/** Adds [sink] with [config] at the end of the configured list and returns its handle. */
internal fun Logger.addOrFail(sink: LogSink, config: SinkConfig = allLevels): InstalledSink {
    configure { it.copy(sinks = it.sinks + ConfiguredSink(sink, config)) }.assertSuccess()
    return sinks.value.single { it.sink === sink }
}

/** Makes [sink] the only configured sink, as a reload that builds every sink anew does. */
internal fun Logger.replaceWith(sink: LogSink) {
    configure { LoggerConfig(listOf(ConfiguredSink(sink, allLevels))) }.assertSuccess()
}

/** Removes [sink] from the configured list. */
internal fun Logger.removeOrFail(sink: LogSink) {
    configure { config -> config.copy(sinks = config.sinks.filter { it.sink !== sink }) }
        .assertSuccess()
}

/** Changes the settings [sink] is paired with. */
internal fun Logger.configureOrFail(sink: LogSink, transform: (SinkConfig) -> SinkConfig) {
    configure { config ->
        config.copy(sinks = config.sinks.map { if (it.sink === sink) it.copy(config = transform(it.config)) else it })
    }.assertSuccess()
}

/** What [InstalledSink.close] returns when this sink failed with [errors]. */
internal fun InstalledSink.closeFailed(vararg errors: SinkError): EmptyResult<CloseError> =
    Err(CloseError(mapOf(name to errors.toList())))

/** The settings a test sink gets unless a test says otherwise: every level. */
internal val allLevels = SinkConfig(LogLevel.VERBOSE)

internal val refused: SinkError = SinkError.Dropped(Thrown(IllegalStateException("refused")))

/** Polls [condition] in real time, which runTest's virtual clock would otherwise skip past. */
internal suspend fun eventually(timeout: Duration = 5.seconds, condition: () -> Boolean) {
    withContext(Dispatchers.Default) {
        withTimeout(timeout) {
            while (!condition()) delay(5.milliseconds)
        }
    }
}

/** Waits [duration] of real time. */
internal suspend fun realDelay(duration: Duration) {
    withContext(Dispatchers.Default) { delay(duration) }
}
