package com.fromwau.kern.logger

@JsName("console")
private external object JsConsole {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
    fun log(message: String)
}

/**
 * [ConsoleOutput.Platform] uses the console method of each severity, so a browser's devtools filter by level.
 * [ConsoleOutput.Stdout] and [ConsoleOutput.Stderr] are `console.log` and `console.error`, which Node.js writes to
 * those streams. Lines are never coloured: a browser console shows ANSI codes as text.
 */
internal actual val consoleWriter: ConsoleWriter = ConsoleWriter { entry, line, config ->
    when (config.console) {
        ConsoleOutput.Off -> Unit
        is ConsoleOutput.Stdout -> JsConsole.log(line)
        is ConsoleOutput.Stderr -> JsConsole.error(line)
        is ConsoleOutput.Platform -> when (entry.level) {
            LogLevel.VERBOSE, LogLevel.DEBUG -> JsConsole.debug(line)
            LogLevel.INFO -> JsConsole.info(line)
            LogLevel.WARN -> JsConsole.warn(line)
            LogLevel.ERROR -> JsConsole.error(line)
        }
    }
}

internal actual fun reportToStandardError(message: String) {
    JsConsole.error(message)
}
