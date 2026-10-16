package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A destination for log entries: the platform's console, a file, a crash reporter, an in-app viewer.
 * [Logger.configure] gives each one its own queue and worker, so a slow or failing sink never holds up the code
 * that logs or the other sinks.
 *
 * ```kotlin
 * val crashes = LogSink("crashes") { entry, _ ->
 *     crashReporter.record(entry.message, entry.throwable)
 *     Ok(Unit)
 * }
 * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(crashes, SinkConfig(level = LogLevel.ERROR))) }
 *     .onError { error -> Log.tag("App").w(error) { "no crash reports" } }
 * ```
 *
 * The properties are read once, when the sink is added to a logger's config. Its level and timestamp are the
 * [SinkConfig] of its [ConfiguredSink] in [LoggerConfig.sinks].
 */
public interface LogSink {
    /** Names the sink in [Logger.sinks], in errors and in health. Unique within a logger's config. */
    public val name: String

    /** What happens to an entry [write] fails on. */
    public val onFailure: SinkFailure
        get() = SinkFailure.Drop()

    /** How many entries may wait for this sink. Past that, [drop] decides which gives way. */
    public val capacity: Int
        get() = 10_000

    /** Which waiting entry gives way when the queue is full. */
    public val drop: DropPolicy
        get() = DropPolicy.OLDEST

    /**
     * Writes one entry. Called one entry at a time and in the order they were logged, mostly on this sink's own
     * worker, never during another write or [close], so it may block. Return an error, or throw, and [onFailure]
     * decides what happens next. An entry logged from inside this call goes to every other sink, never back to this
     * one.
     *
     * @param config the settings the sink has in the logger's config right now; honour its
     *   [SinkConfig.timestamp] when rendering a line.
     */
    public fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError>

    /**
     * Releases what the sink holds open, after its queue has drained or the close timed out. A logger never calls it
     * while it is calling [write]. The next entry after a close is written as usual, so reopen whatever [write]
     * needs. An error returned, or a throw, is a failure of the sink like a failed write.
     */
    public fun close(): EmptyResult<SinkError> = Ok(Unit)
}

/**
 * A [LogSink] from a lambda.
 *
 * @param name names the sink in [Logger.sinks], in errors and in health; unique within a logger's config.
 * @param onFailure what happens to an entry [write] fails on: dropped by default.
 * @param capacity how many entries may wait for the sink, 10,000 by default.
 * @param drop which waiting entry gives way when the queue is full: the oldest by default.
 * @param write writes one entry, given the sink's current settings. It runs on the sink's own worker for each entry
 *   the sink's level takes, one at a time and in the order they were logged, so it may block. It is also handed
 *   the logger's warnings about entries this sink dropped, whatever its level. Return an error, or throw, and
 *   [onFailure] decides what happens next.
 */
public fun LogSink(
    name: String,
    onFailure: SinkFailure = SinkFailure.Drop(),
    capacity: Int = 10_000,
    drop: DropPolicy = DropPolicy.OLDEST,
    write: (entry: LogEntry, config: SinkConfig) -> EmptyResult<SinkError>,
): LogSink = LambdaSink(name, onFailure, capacity, drop, write)

private class LambdaSink(
    override val name: String,
    override val onFailure: SinkFailure,
    override val capacity: Int,
    override val drop: DropPolicy,
    private val writer: (LogEntry, SinkConfig) -> EmptyResult<SinkError>,
) : LogSink {
    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> = writer(entry, config)
}

/**
 * A sink's settings, given with it in a [ConfiguredSink].
 *
 * @param level the least severe entry the sink takes. An entry no configured sink takes never builds its message.
 * @param timestamp whether a [LogFormat.TEXT] line starts with its time.
 */
public data class SinkConfig(
    val level: LogLevel = LogLevel.INFO,
    val timestamp: Timestamp = Timestamp.On,
)

/** Whether a [LogFormat.TEXT] line carries its time. A [LogFormat.JSON] line always does. */
public enum class Timestamp {
    /** Each line starts with its local time, to the millisecond, with the offset from UTC. */
    On,

    /** The line starts at the level, for a collector such as journald that stamps each line itself. */
    Off,
}

/** What a sink's worker does with an entry the sink failed to write. */
public sealed interface SinkFailure {
    /**
     * The entry is lost to this sink and counted in [SinkHealth.dropped], and so is every entry that reaches the
     * worker during the next [backoff], without calling the sink. The next entry written is preceded by a warning
     * saying how many were dropped, at most one such warning a second; a close writes one still pending.
     */
    public data class Drop(val backoff: Duration = 1.seconds) : SinkFailure

    /**
     * The entry stays first in line and the worker tries it again after [backoff], by itself, until it is
     * written; later entries wait behind it, and past [LogSink.capacity] give way by [LogSink.drop]. A flush or
     * close cuts the backoff short. For a destination where every entry matters, such as a log file.
     */
    public data class Retry(val backoff: Duration = 1.seconds) : SinkFailure
}

/** Which entry gives way when a sink's queue is full. */
public enum class DropPolicy {
    /** The oldest waiting entry, which keeps the most recent context. */
    OLDEST,

    /** The entry arriving, which keeps the lead-up to the failure. */
    NEWEST,
}
