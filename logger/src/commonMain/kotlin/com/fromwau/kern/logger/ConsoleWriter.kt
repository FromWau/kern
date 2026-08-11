package com.fromwau.kern.logger

/** Where a rendered line goes on this platform. */
internal fun interface ConsoleWriter {
    fun write(entry: LogEntry, line: String, state: LoggerRuntimeState)
}

internal expect val consoleWriter: ConsoleWriter

/**
 * Prints the rendered line as-is, which keeps a [LogFormat.JSON] run to one parseable object per line.
 * The platform default everywhere except Android, whose logcat wants the tag and message apart.
 */
internal val stdoutConsoleWriter: ConsoleWriter = ConsoleWriter { entry, line, state ->
    println(if (state.color) colorize(line, entry.level) else line)
}
