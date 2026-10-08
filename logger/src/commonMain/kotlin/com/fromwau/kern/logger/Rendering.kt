package com.fromwau.kern.logger

import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.blue
import com.fromwau.kern.terminal.brightBlack
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.white
import com.fromwau.kern.terminal.yellow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.format
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.char
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

internal fun LogEntry.render(
    format: LogFormat,
    localClock: LocalClock,
): String = when (format) {
    LogFormat.TEXT -> toTextLine(localClock)
    LogFormat.JSON -> toJsonLine()
}

internal fun LogEntry.toTextLine(localClock: LocalClock): String =
    textLine(localClock).mapLoneSurrogates { REPLACEMENT }

// A JSON escape keeps a lone surrogate exactly, and a JSON reader gets the original string back.
internal fun LogEntry.toJsonLine(): String =
    jsonLine().mapLoneSurrogates { "\\u${it.code.toString(16).padStart(4, '0')}" }

private fun LogEntry.textLine(localClock: LocalClock): String = buildString {
    append(timestamp.format(TEXT_TIMESTAMP, localClock.offsetAt(timestamp)))
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

private fun LogEntry.jsonLine(): String = json.encodeToString(
    JsonLine(
        timestamp = timestamp.format(JSON_TIMESTAMP),
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
