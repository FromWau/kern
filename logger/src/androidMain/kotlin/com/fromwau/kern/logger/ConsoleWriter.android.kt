package com.fromwau.kern.logger

import android.util.Log as Logcat

/**
 * logcat carries the tag, severity and timestamp itself, so it is given the message alone rather than a
 * rendered line. [LoggerRuntimeState.format] and [LoggerRuntimeState.color] therefore shape the log file
 * here, not the console; set a [LoggerRuntimeState.file] to get JSON on Android.
 */
internal actual val consoleWriter: ConsoleWriter = ConsoleWriter { entry, _, _ ->
    val message = entry.fields
        .entries
        .joinToString(prefix = entry.message, separator = "") { (key, value) -> " $key=$value" }

    when (entry.level) {
        LogLevel.VERBOSE -> Logcat.v(entry.tag, message, entry.throwable)
        LogLevel.DEBUG -> Logcat.d(entry.tag, message, entry.throwable)
        LogLevel.INFO -> Logcat.i(entry.tag, message, entry.throwable)
        LogLevel.WARN -> Logcat.w(entry.tag, message, entry.throwable)
        LogLevel.ERROR -> Logcat.e(entry.tag, message, entry.throwable)
    }
}
