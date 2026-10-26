package com.fromwau.kern.logger

import com.fromwau.kern.result.Ok
import android.util.Log as Logcat

/**
 * Logcat carries the tag, severity and timestamp itself, so it is given the message, fields and error rather than
 * a rendered line.
 */
internal actual fun platformWriter(): EntryWriter = EntryWriter { entry, _ ->
    val message = entry.summary()
    try {
        write(entry.level, entry.tag, message, entry.throwable)
    } catch (_: Throwable) {
        // Logcat renders the trace unguarded: a throwable whose toString() throws would fail every attempt.
        write(entry.level, entry.tag, entry.body(), throwable = null)
    }
    Ok(Unit)
}

private fun write(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
    when (level) {
        LogLevel.VERBOSE -> Logcat.v(tag, message, throwable)
        LogLevel.DEBUG -> Logcat.d(tag, message, throwable)
        LogLevel.INFO -> Logcat.i(tag, message, throwable)
        LogLevel.WARN -> Logcat.w(tag, message, throwable)
        LogLevel.ERROR -> Logcat.e(tag, message, throwable)
        // Log.wtf may end the process under some system settings, which a logger must never do.
        LogLevel.WTF, LogLevel.OFF -> Logcat.println(Logcat.ASSERT, tag, message.withStackTrace(throwable))
    }
}

private fun String.withStackTrace(throwable: Throwable?): String =
    if (throwable == null) this else "$this\n${Logcat.getStackTraceString(throwable)}"
