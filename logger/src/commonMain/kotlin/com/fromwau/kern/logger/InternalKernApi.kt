@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.logger

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant

/** Marks what kern's own sink modules use from the logger's core. It may change in any release. */
@RequiresOptIn(
    message = "For kern's own modules, such as logger-file; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY)
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
 * Reports that [what] threw [thrown] while the sink whose write or close runs on this thread rendered an entry, such
 * as `field "count": its toString() threw`: with the stack trace, to standard error and as an error entry to every
 * other sink, once per [what] and kind of throw for that sink, and for at most 64 of them per sink. Called from
 * anywhere else, it writes to standard error alone, every time, and straight there: [reportFailuresTo] does not
 * reach it.
 */
@InternalKernApi
public fun reportRenderingThrew(what: String, thrown: Throwable) {
    val writer = currentWriter
    if (writer != null) return writer.renderingThrew(what, thrown)
    unownedRenderingReport.load()("$INTERNAL_TAG: could not render $what\n${thrown.stackTraceOrPlaceholder()}")
}

private val unownedRenderingReport = AtomicReference<(String) -> Unit>(::reportToStandardErrorOrDrop)

/**
 * For kern's own tests: runs [block] with what [reportRenderingThrew] writes outside a sink's write or close sent to
 * [report], not standard error.
 */
@InternalKernApi
public fun <T> withRenderingReportsTo(report: (String) -> Unit, block: () -> T): T {
    val previous = unownedRenderingReport.exchange(report)
    try {
        return block()
    } finally {
        unownedRenderingReport.store(previous)
    }
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

/** Whether an entry at this level reaches a sink set to [threshold], ranked by severity as the logger ranks it. */
@InternalKernApi
public fun LogLevel.reaches(threshold: LogLevel): Boolean = severity >= threshold.severity

/**
 * For kern's own tests: sends this logger's one-line reports of its own failures to [report], not standard error. A
 * [reportRenderingThrew] outside a sink's write or close still goes to standard error.
 */
@InternalKernApi
public fun Logger.reportFailuresTo(report: (String) -> Unit) {
    this.report = report
}
