package com.fromwau.kern.logger

import com.fromwau.kern.result.ResultError
import kotlin.time.Instant

/**
 * One logged event, after its message has been built and before it has been rendered.
 *
 * A [LogSink] is handed these rather than finished lines, so a sink that forwards logs somewhere reads [fields],
 * [throwable] and [error] as data instead of parsing them back out of text.
 *
 * @param timestamp when it was logged, or the time handed to [Logger.log] when an entry is forwarded.
 * @param fields structured values by key, as logged: a number, a `Boolean`, null, or anything else, which is
 *   written as text, an array or a collection as its contents. [LogFormat.JSON] writes them as a nested `fields`
 *   object; [LogFormat.TEXT] appends them as `key=value` after the message.
 * @param throwable the failure behind the entry. Its stack trace is rendered under the message rather than folded
 *   into it, so the message stays one readable line.
 * @param error the typed error behind the entry, rendered as its `toString()`: `error=...` in text, `"error"` in
 *   JSON.
 */
public data class LogEntry(
    val timestamp: Instant,
    val tag: String,
    val level: LogLevel,
    val message: String,
    val fields: Map<String, Any?> = emptyMap(),
    val throwable: Throwable? = null,
    val error: ResultError? = null,
)
