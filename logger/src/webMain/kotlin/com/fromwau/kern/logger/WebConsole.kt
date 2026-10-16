package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok

@JsName("console")
private external object JsConsole {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

/**
 * The console method of each severity, so a browser's devtools filter by level. Lines are never coloured: a
 * browser console shows ANSI codes as text.
 */
internal actual fun platformWriter(): (LogEntry, SinkConfig) -> EmptyResult<SinkError> {
    val localClock by lazy { systemLocalClock() }
    return { entry, config ->
        val line = entry.render(LogFormat.TEXT, localClock, config.timestamp)
        when (entry.level) {
            LogLevel.VERBOSE, LogLevel.DEBUG -> JsConsole.debug(line)
            LogLevel.INFO -> JsConsole.info(line)
            LogLevel.WARN -> JsConsole.warn(line)
            LogLevel.ERROR, LogLevel.WTF, LogLevel.OFF -> JsConsole.error(line)
        }
        Ok(Unit)
    }
}

internal actual fun reportToStandardError(message: String) {
    JsConsole.error(message)
}
