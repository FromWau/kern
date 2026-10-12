package com.fromwau.kern.logger

import kotlin.time.Clock

/** The tag on every entry the logger writes about itself. */
internal const val INTERNAL_TAG = "kern.logger"

internal fun internalEntry(level: LogLevel, message: String, fields: Map<String, Any?> = emptyMap()) =
    LogEntry(Clock.System.now(), INTERNAL_TAG, level, message, fields)

/** The entry that stands where [gap]'s entries were dropped, after [destination] refused them. */
internal fun gapEntry(gap: Gap, destination: String) = internalEntry(
    LogLevel.WARN,
    "dropped ${entries(gap.count)} $destination refused, logged ${gap.span()}",
    fields = mapOf("dropped" to gap.count),
)
