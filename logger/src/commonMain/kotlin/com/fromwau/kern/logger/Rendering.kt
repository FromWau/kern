@file:OptIn(InternalKernApi::class)

package com.fromwau.kern.logger

import com.fromwau.kern.result.ResultError
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Instant

private const val LEVEL_COLUMN = 7
private const val TAG_COLUMN = 35

// Defaults are dropped from the output, so an entry without fields or a throwable stays a short line.
private val json = Json

/**
 * The wire shape of a [LogFormat.JSON] line. [LogEntry] stays free of serialization so a consumer is not
 * forced to make their own [Throwable] serializable; the stack trace is rendered to text here instead.
 */
@Serializable
private data class JsonLine(
    @SerialName("timestamp") val timestamp: String,
    @SerialName("tag") val tag: String,
    @SerialName("level") val level: String,
    @SerialName("message") val message: String,
    @SerialName("fields") val fields: JsonObject = JsonObject(emptyMap()),
    @SerialName("stackTrace") val stackTrace: String? = null,
    @SerialName("error") val error: String? = null,
)

internal fun LogEntry.render(
    format: LogFormat,
    localClock: LocalClock,
    timestamp: Timestamp,
): String = when (format) {
    LogFormat.TEXT -> toTextLine(localClock, timestamp)
    LogFormat.JSON -> toJsonLine()
}

internal fun LogEntry.toTextLine(localClock: LocalClock, timestamp: Timestamp = Timestamp.On): String =
    textLine(localClock, timestamp).mapLoneSurrogates { REPLACEMENT }

// A JSON escape keeps a lone surrogate exactly, and a JSON reader gets the original string back.
internal fun LogEntry.toJsonLine(): String =
    jsonLine().mapLoneSurrogates { "\\u${it.code.toString(16).padStart(4, '0')}" }

private fun LogEntry.textLine(localClock: LocalClock, stamp: Timestamp): String = buildString {
    if (stamp == Timestamp.On) {
        append(timestamp.format(TEXT_TIMESTAMP, localClock.offsetAt(timestamp)))
        append(' ')
    }
    append(level.name.padEnd(LEVEL_COLUMN))
    append(' ')
    append(tag.take(TAG_COLUMN))
    append(" - ")
    append(body())
}

/** The message, its fields and error, then the stack trace on the lines below: a text line without its prefix. */
internal fun LogEntry.body(): String = buildString {
    append(summary())
    throwable?.let {
        append('\n')
        append(it.safeStackTrace())
    }
}

/** The message followed by its fields and its error, for a log that takes the stack trace separately. */
internal fun LogEntry.summary(): String = message + fields.asText() + error.asErrorText()

private fun LogEntry.jsonLine(): String = json.encodeToString(
    JsonLine(
        timestamp = timestamp.format(JSON_TIMESTAMP),
        tag = tag,
        level = level.name,
        message = message,
        fields = JsonObject(fields.mapValues { (_, value) -> value.toJsonField() }),
        stackTrace = throwable?.safeStackTrace(),
        error = error?.let(::fieldText),
    ),
)

private fun Any?.toJsonField(): JsonElement = when (val typed = this?.let(::typedFieldValue)) {
    null -> JsonNull
    is String -> JsonPrimitive(typed)
    is Boolean -> JsonPrimitive(typed)
    else -> JsonPrimitive(typed as Number)
}

/** The error as ` error=<text>`, ready to follow the fields, or nothing without one. */
private fun ResultError?.asErrorText(): String = if (this == null) "" else " error=${fieldText(this)}"

/** The fields as ` key=value` pairs, ready to follow the message. */
private fun Map<String, Any?>.asText(): String = entries.joinToString(separator = "") { (key, value) ->
    " $key=${fieldText(value)}"
}

/** The stack trace, or a placeholder when building it throws, as a `toString()` or `message` that throws makes it. */
internal fun Throwable.safeStackTrace(): String = try {
    stackTraceToString()
} catch (e: Throwable) {
    "${this::class.simpleName}: <stack trace threw ${e::class.simpleName}>"
}

// ISO 8601 with a fixed number of fraction digits, so stamps of one offset sort as text in time order.
private fun timestampFormat(fractionDigits: Int) = DateTimeComponents.Format {
    date(LocalDate.Formats.ISO)
    char('T')
    hour()
    char(':')
    minute()
    char(':')
    second()
    char('.')
    secondFraction(fixedLength = fractionDigits)
    offset(UtcOffset.Formats.ISO)
}

private val TEXT_TIMESTAMP = timestampFormat(fractionDigits = 3)
private val JSON_TIMESTAMP = timestampFormat(fractionDigits = 9)

private val REPLACEMENT = Char(0xFFFD).toString()

/**
 * Rewrites every half of a surrogate pair that stands alone, as a string cut through an emoji has. UTF-8 has no
 * bytes for one, and the JVM would write `?` where native writes U+FFFD.
 */
private inline fun String.mapLoneSurrogates(replacement: (Char) -> String): String {
    if (none { it.isSurrogate() }) return this

    val source = this
    return buildString {
        var index = 0
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                char.isHighSurrogate() && next != null && next.isLowSurrogate() -> {
                    append(char)
                    append(next)
                    index++
                }
                char.isSurrogate() -> append(replacement(char))
                else -> append(char)
            }
            index++
        }
    }
}
