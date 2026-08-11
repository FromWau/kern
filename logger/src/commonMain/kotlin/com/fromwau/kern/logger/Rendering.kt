package com.fromwau.kern.logger

import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.blue
import com.fromwau.kern.terminal.brightBlack
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.white
import com.fromwau.kern.terminal.yellow
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
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
    @SerialName("fields") val fields: Map<String, String> = emptyMap(),
    @SerialName("stackTrace") val stackTrace: String? = null,
)

internal fun LogEntry.render(format: LogFormat): String = when (format) {
    LogFormat.TEXT -> toTextLine()
    LogFormat.JSON -> toJsonLine()
}

internal fun LogEntry.toTextLine(): String = buildString {
    append(formatLocal(timestamp))
    append(' ')
    append(level.name.padEnd(LEVEL_COLUMN))
    append(' ')
    append(tag.take(TAG_COLUMN))
    append(" - ")
    append(message)
    fields.forEach { (key, value) ->
        append(' ')
        append(key)
        append('=')
        append(value)
    }
    throwable?.let {
        append('\n')
        append(it.stackTraceToString())
    }
}

internal fun LogEntry.toJsonLine(): String = json.encodeToString(
    JsonLine(
        timestamp = timestamp.toString(),
        tag = tag,
        level = level.name,
        message = message,
        fields = fields,
        stackTrace = throwable?.stackTraceToString(),
    ),
)

/** The colour each severity prints in. */
private val LogLevel.style: Style
    get() = when (this) {
        LogLevel.VERBOSE -> brightBlack
        LogLevel.DEBUG -> blue
        LogLevel.INFO -> white
        LogLevel.WARN -> yellow
        LogLevel.ERROR -> red
    }

/** Colours [line] for its severity, or returns it untouched when [enabled] is false. */
internal fun colorize(line: String, level: LogLevel, enabled: Boolean): String = level.style.render(line, enabled)

private fun formatLocal(timestamp: Instant): String =
    format(timestamp.toLocalDateTime(TimeZone.currentSystemDefault()))

private fun format(dateTime: LocalDateTime): String {
    val year = dateTime.year.toString().padStart(4, '0')
    val month = dateTime.month.number.toString().padStart(2, '0')
    val day = dateTime.day.toString().padStart(2, '0')
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')
    val second = dateTime.second.toString().padStart(2, '0')
    val milli = (dateTime.nanosecond / 1_000_000).toString().padStart(3, '0')

    return "$year-$month-$day $hour:$minute:$second.$milli"
}
