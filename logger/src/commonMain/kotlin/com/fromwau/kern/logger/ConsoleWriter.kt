package com.fromwau.kern.logger

/** Where a rendered line goes on this platform. */
internal fun interface ConsoleWriter {
    fun write(entry: LogEntry, line: String, config: LoggerConfig)
}

internal expect val consoleWriter: ConsoleWriter

/** Writes one line about the logger's own failure where an operator looks first: standard error. */
internal expect fun reportToStandardError(message: String)
