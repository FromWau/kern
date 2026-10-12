package com.fromwau.kern.logger

/**
 * What the logger has failed to deliver, as read from [Logger.health]. Every count is since the logger was
 * created.
 *
 * @param file the log file. Its counts carry over when [LoggerConfig.file] moves to another path.
 * @param sinks one per [LogSink], in the order they were handed to [Logger].
 * @param heldDropped how many entries [Logger.holdUntilConfigured] dropped because more than 1024 were logged
 *   before [Logger.configure].
 */
public data class LoggerHealth(
    val file: BufferHealth = BufferHealth(),
    val sinks: List<SinkHealth> = emptyList(),
    val heldDropped: Long = 0,
)

/**
 * A destination that holds refused entries for later, such as the log file or a [RetryingSink].
 *
 * @param failures how many writes failed.
 * @param buffered how many entries are held right now, waiting for a retry.
 * @param dropped how many entries were lost for good.
 * @param lastError the most recent failure, or null if there was none.
 */
public data class BufferHealth(
    val failures: Long = 0,
    val buffered: Int = 0,
    val dropped: Long = 0,
    val lastError: Throwable? = null,
)

/**
 * One [LogSink].
 *
 * @param failures how many calls threw.
 * @param dropped how many entries the sink never got, from a failure or while its breaker was open.
 * @param lastError the most recent failure, or null if there was none.
 * @param breakerOpen whether [Breaker.Trip] has tripped and the sink is skipped until a retry succeeds.
 */
public data class SinkHealth(
    val failures: Long = 0,
    val dropped: Long = 0,
    val lastError: Throwable? = null,
    val breakerOpen: Boolean = false,
)
