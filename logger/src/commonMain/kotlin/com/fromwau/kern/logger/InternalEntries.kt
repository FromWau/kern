package com.fromwau.kern.logger

import kotlin.time.Clock
import kotlin.time.Instant

/** The tag on every entry the logger writes about itself. */
internal const val INTERNAL_TAG = "kern.logger"

internal fun internalEntry(level: LogLevel, message: String, fields: Map<String, Any?> = emptyMap()) =
    LogEntry(Clock.System.now(), INTERNAL_TAG, level, message, fields)

/** Entries a sink never got, between the first and the last one's timestamp. */
internal data class Gap(val count: Long, val from: Instant, val to: Instant) {
    fun span(): String = if (from == to) "at $from" else "between $from and $to"

    fun plus(other: Gap?): Gap = if (other == null) {
        this
    } else {
        Gap(count + other.count, minOf(from, other.from), maxOf(to, other.to))
    }
}

internal fun Gap?.plus(entry: LogEntry): Gap = (this ?: Gap(0, entry.timestamp, entry.timestamp))
    .plus(Gap(1, entry.timestamp, entry.timestamp))

internal fun entries(count: Long): String = if (count == 1L) "1 entry" else "$count entries"

/** The entry that stands where [gap]'s entries were dropped, written to the sink they were meant for. */
internal fun gapEntry(gap: Gap) = internalEntry(
    LogLevel.WARN,
    "dropped ${entries(gap.count)} this sink did not take, logged ${gap.span()}",
    fields = mapOf("dropped" to gap.count),
)
