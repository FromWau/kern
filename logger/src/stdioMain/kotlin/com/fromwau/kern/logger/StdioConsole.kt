package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.blue
import com.fromwau.kern.terminal.bold
import com.fromwau.kern.terminal.brightBlack
import com.fromwau.kern.terminal.defaultTerminal
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.white
import com.fromwau.kern.terminal.yellow

/**
 * Built once, not per line: on Windows the first call opts the console into virtual-terminal processing and
 * UTF-8 output, and both have to land before anything is written.
 */
internal val console: Terminal by lazy(LazyThreadSafetyMode.PUBLICATION) { defaultTerminal() }

/**
 * Writes each entry as a line to [stream], coloured for its severity when [color] allows it. A [LogFormat.JSON]
 * line is never coloured, so it stays one parseable object per line.
 */
internal fun streamWriter(
    stream: Stream,
    format: LogFormat,
    color: ColorMode,
    terminal: Terminal = console,
): (LogEntry, SinkConfig) -> EmptyResult<SinkError> {
    val localClock by lazy(LazyThreadSafetyMode.PUBLICATION) { systemLocalClock() }
    return { entry, config ->
        val line = entry.render(format, localClock, config.timestamp)
        val terminalTakesColor = if (stream == Stream.Out) terminal.ansi else terminal.errAnsi
        val text = colorize(line, entry.level, enabled = colors(format, color, terminalTakesColor)) + "\n"
        if (stream == Stream.Out) terminal.out(text) else terminal.err(text)
        val failure = terminal.writeResult(stream).errorOrNull()
        if (failure != null) Err(SinkError.Dropped(failure)) else Ok(Unit)
    }
}

/** The [PlatformSink] writer where the program's own terminal is the platform's log: coloured text on stdout. */
internal fun terminalWriter(): (LogEntry, SinkConfig) -> EmptyResult<SinkError> =
    streamWriter(Stream.Out, LogFormat.TEXT, ColorMode.AUTO)

/** Whether a console line is coloured, given what the stream's terminal would accept under `AUTO`. */
internal fun colors(format: LogFormat, mode: ColorMode, terminalTakesColor: Boolean): Boolean =
    format == LogFormat.TEXT && when (mode) {
        ColorMode.AUTO -> terminalTakesColor
        ColorMode.ALWAYS -> true
        ColorMode.NEVER -> false
    }

internal actual fun reportToStandardError(message: String) {
    console.err("$message\n")
}

/** The colour each severity prints in. */
private val LogLevel.style: Style
    get() = when (this) {
        LogLevel.VERBOSE -> brightBlack
        LogLevel.DEBUG -> blue
        LogLevel.INFO, LogLevel.OFF -> white
        LogLevel.WARN -> yellow
        LogLevel.ERROR -> red
        LogLevel.WTF -> bold + red
    }

/** Colours [line] for its severity, or returns it untouched when [enabled] is false. */
internal fun colorize(line: String, level: LogLevel, enabled: Boolean): String = level.style.render(line, enabled)
