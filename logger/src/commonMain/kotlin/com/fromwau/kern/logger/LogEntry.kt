package com.fromwau.kern.logger

import kotlin.time.Instant

/**
 * One logged event, after its message has been built and before it has been rendered.
 *
 * A [LogSink] is handed these rather than finished lines, so a sink that forwards logs somewhere reads
 * [fields] and [throwable] as data instead of parsing them back out of text.
 *
 * @param fields structured key/value pairs. [LogFormat.JSON] writes them as a nested `fields` object;
 *   [LogFormat.TEXT] appends them as `key=value` after the message.
 * @param throwable the failure behind the entry. Its stack trace is rendered under the message rather
 *   than folded into it, so the message stays one readable line.
 */
public data class LogEntry(
    val timestamp: Instant,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val fields: Map<String, String> = emptyMap(),
    val throwable: Throwable? = null,
)
