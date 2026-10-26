@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import com.fromwau.kern.result.Ok
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ptr
import platform.Foundation.NSBundle
import platform.darwin.OS_LOG_TYPE_DEBUG
import platform.darwin.OS_LOG_TYPE_DEFAULT
import platform.darwin.OS_LOG_TYPE_ERROR
import platform.darwin.OS_LOG_TYPE_FAULT
import platform.darwin.OS_LOG_TYPE_INFO
import platform.darwin.__dso_handle
import platform.darwin._os_log_internal
import platform.darwin.os_log_create
import platform.darwin.os_log_t
import platform.darwin.os_log_type_t

/**
 * The unified log (`os_log`) in an app, where Xcode, Console.app and device logs read it, and standard output in a
 * command-line tool, which has no bundle and whose user reads the terminal.
 *
 * The unified log carries the timestamp, level and category itself, so it is given the message, fields, error and
 * stack trace rather than a rendered line, under the app's bundle identifier with the tag as the category. The
 * text is marked public, so a release build does not redact it as `<private>`.
 */
internal actual fun platformWriter(): EntryWriter {
    val subsystem = bundleIdentifier ?: return terminalWriter()
    return EntryWriter { entry, _ ->
        _os_log_internal(
            __dso_handle.ptr,
            logFor(subsystem, entry.tag),
            entry.level.osLogType,
            "%{public}s",
            entry.body(),
        )
        Ok(Unit)
    }
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
        LogLevel.WTF, LogLevel.OFF -> OS_LOG_TYPE_FAULT
    }
