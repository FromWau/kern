package com.fromwau.kern.logger

import kotlin.time.Instant

/** Marks what kern's own sink modules use from the logger's core. It may change in any release. */
@RequiresOptIn(
    message = "For kern's own modules, such as logger-file; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
public annotation class InternalKernApi

/**
 * Counts [count] entries that the sink whose write or close is running on this thread lost by itself, logged between
 * [from] and [to], as dropped: in its [SinkHealth.dropped] and in the warning that stands in their place. Called
 * from anywhere else, it does nothing.
 */
@InternalKernApi
public fun reportDroppedBySink(count: Long, from: Instant, to: Instant) {
    if (count > 0) currentWriter?.droppedBySink(Gap(count, from, to))
}

/**
 * Renders an entry as one line in [format], without its line break, the way the built-in sinks do. The local zone
 * of a [LogFormat.TEXT] line is looked up once, on the first entry.
 */
@InternalKernApi
public fun lineRenderer(format: LogFormat): (entry: LogEntry, timestamp: Timestamp) -> String {
    val localClock by lazy(LazyThreadSafetyMode.PUBLICATION) { systemLocalClock() }
    return { entry, timestamp -> entry.render(format, localClock, timestamp) }
}
