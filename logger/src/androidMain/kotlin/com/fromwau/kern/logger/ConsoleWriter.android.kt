package com.fromwau.kern.logger

import android.util.Log as Logcat

/**
 * [ConsoleOutput.Platform] is logcat, which carries the tag, severity and timestamp itself, so it is given the
 * message and fields rather than a rendered line, whatever its format says.
 * [ConsoleOutput.Stdout] and [ConsoleOutput.Stderr] write the rendered line to those streams, which logcat shows
 * under `System.out` and `System.err`.
 */
internal actual val consoleWriter: ConsoleWriter = ConsoleWriter { entry, line, config ->
    if (config.console !is ConsoleOutput.Platform) return@ConsoleWriter stdioConsoleWriter.write(entry, line, config)

    val message = entry.message + entry.fields.asText()

    when (entry.level) {
        LogLevel.VERBOSE -> Logcat.v(entry.tag, message, entry.throwable)
        LogLevel.DEBUG -> Logcat.d(entry.tag, message, entry.throwable)
        LogLevel.INFO -> Logcat.i(entry.tag, message, entry.throwable)
        LogLevel.WARN -> Logcat.w(entry.tag, message, entry.throwable)
        LogLevel.ERROR -> Logcat.e(entry.tag, message, entry.throwable)
    }
}
