package com.fromwau.kern.logger

import com.fromwau.kern.terminal.Style
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.blue
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
 * Prints the rendered line to the stream [LoggerConfig.console] names, with [ConsoleOutput.Platform] meaning
 * standard output. A [LogFormat.JSON] line is never coloured, so it stays one parseable object per line.
 */
internal val stdioConsoleWriter: ConsoleWriter = ConsoleWriter { entry, line, config ->
    val output = config.console
    val (write, terminalTakesColor, mode) = when (output) {
        ConsoleOutput.Off -> return@ConsoleWriter
        is ConsoleOutput.Platform -> Triple(console::out, console.ansi, output.color)
        is ConsoleOutput.Stdout -> Triple(console::out, console.ansi, output.color)
        is ConsoleOutput.Stderr -> Triple(console::err, console.errAnsi, output.color)
    }
    val format = output.format ?: return@ConsoleWriter
    write(colorize(line, entry.level, enabled = colors(format, mode, terminalTakesColor)) + "\n")
}

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
        LogLevel.INFO -> white
        LogLevel.WARN -> yellow
        LogLevel.ERROR -> red
    }

/** Colours [line] for its severity, or returns it untouched when [enabled] is false. */
internal fun colorize(line: String, level: LogLevel, enabled: Boolean): String = level.style.render(line, enabled)
