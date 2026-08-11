package com.fromwau.kern.logger

import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.defaultTerminal

/** Where a rendered line goes on this platform. */
internal fun interface ConsoleWriter {
    fun write(entry: LogEntry, line: String, state: LoggerRuntimeState)
}

internal expect val consoleWriter: ConsoleWriter

/**
 * Built once, not per line: on Windows the first call opts the console into virtual-terminal processing and
 * UTF-8 output, and both have to land before anything is written.
 */
internal val console: Terminal by lazy(LazyThreadSafetyMode.PUBLICATION) { defaultTerminal() }

/**
 * Prints the rendered line as-is, which keeps a [LogFormat.JSON] run to one parseable object per line.
 * The platform default everywhere except Android, whose logcat wants the tag and message apart.
 *
 * Colour needs [LoggerRuntimeState.color] *and* a console that should receive it, so a redirected or piped
 * run writes plain text and `NO_COLOR` is obeyed without the caller doing anything.
 */
internal val stdoutConsoleWriter: ConsoleWriter = ConsoleWriter { entry, line, state ->
    console.out(colorize(line, entry.level, enabled = state.color && console.ansi) + "\n")
}
