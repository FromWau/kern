@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.Foundation.NSBundle
import platform.darwin.OS_LOG_TYPE_DEBUG
import platform.darwin.OS_LOG_TYPE_DEFAULT
import platform.darwin.OS_LOG_TYPE_ERROR
import platform.darwin.OS_LOG_TYPE_INFO
import platform.darwin.__dso_handle
import platform.darwin._os_log_internal
import platform.darwin.os_log_create
import platform.darwin.os_log_t
import platform.darwin.os_log_type_t

/**
 * [ConsoleOutput.Platform] is the unified log (`os_log`) in an app, where Xcode, Console.app and device logs read
 * it, and standard output in a command-line tool, which has no bundle and whose user reads the terminal.
 *
 * The unified log carries the timestamp, level and category itself, so it is given the message, fields and stack
 * trace rather than a rendered line, under the app's bundle identifier with the tag as the category. The text is
 * marked public, so a release build does not redact it as `<private>`.
 */
internal actual val consoleWriter: ConsoleWriter = ConsoleWriter { entry, line, config ->
    val subsystem = bundleIdentifier
    if (config.console !is ConsoleOutput.Platform || subsystem == null) {
        return@ConsoleWriter stdioConsoleWriter.write(entry, line, config)
    }

    _os_log_internal(__dso_handle.ptr, logFor(subsystem, entry.tag), entry.level.osLogType, "%{public}s", entry.text())
}

private val bundleIdentifier: String? = NSBundle.mainBundle.bundleIdentifier

private val logsLock = reentrantLock()
private val logs = mutableMapOf<String, os_log_t>()

// One log object per tag for the life of the process, rather than one per line.
private fun logFor(subsystem: String, category: String): os_log_t =
    logsLock.withLock { logs.getOrPut(category) { os_log_create(subsystem, category) } }

private val LogLevel.osLogType: os_log_type_t
    get() = when (this) {
        LogLevel.VERBOSE, LogLevel.DEBUG -> OS_LOG_TYPE_DEBUG
        LogLevel.INFO -> OS_LOG_TYPE_INFO
        LogLevel.WARN -> OS_LOG_TYPE_DEFAULT
        LogLevel.ERROR -> OS_LOG_TYPE_ERROR
    }

private fun LogEntry.text(): String = buildString {
    append(message)
    append(fields.asText())
    throwable?.let {
        append('\n')
        append(it.stackTraceToString())
    }
}
