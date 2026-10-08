package com.fromwau.kern.logger

import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal

/** Where a rendered line goes on this platform. */
internal fun interface ConsoleWriter {
    fun write(entry: LogEntry, line: String, config: LoggerConfig)
}

internal expect val consoleWriter: ConsoleWriter

/**
 * Built once, not per line: on Windows the first call opts the console into virtual-terminal processing and
 * UTF-8 output, and both have to land before anything is written.
 */
internal val console: Terminal by lazy(LazyThreadSafetyMode.PUBLICATION) { defaultTerminal() }

/**
 * Prints the rendered line to the stream [LoggerConfig.console] names. The platform default everywhere except
 * Android, whose logcat wants the tag and message apart.
 *
 * Colour needs [LoggerConfig.color] *and* a terminal on the stream being written, so a redirected or piped
 * stream gets plain text and `NO_COLOR` is obeyed without the caller doing anything. A [LogFormat.JSON] line
 * is never coloured, so it stays one parseable object per line.
 */
internal val stdioConsoleWriter: ConsoleWriter = ConsoleWriter { entry, line, config ->
    val (write, terminalTakesColor) = when (config.console) {
        Console.OFF -> return@ConsoleWriter
        Console.STDOUT -> console::out to console.ansi
        Console.STDERR -> console::err to console.errAnsi
    }
    val colored = config.format == LogFormat.TEXT && config.color && terminalTakesColor

    write(colorize(line, entry.level, enabled = colored) + "\n")
}
