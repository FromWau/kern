package com.fromwau.kern.logger

/**
 * Apple targets print rather than calling `NSLog`, which would stamp its own timestamp and process name
 * onto a line that already carries one, and would break a [LogFormat.JSON] run into unparseable output.
 * Route to `os_log` with a [LogSink] if you want it.
 */
internal actual val consoleWriter: ConsoleWriter = stdoutConsoleWriter
