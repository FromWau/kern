@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.CloseHandle
import platform.windows.DWORDVar
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.FALSE
import platform.windows.GetCurrentProcessId
import platform.windows.GetExitCodeProcess
import platform.windows.GetLastError
import platform.windows.OpenProcess
import platform.windows.PROCESS_QUERY_LIMITED_INFORMATION
import platform.windows.STILL_ACTIVE

public actual fun currentProcessId(): Long = GetCurrentProcessId().toLong()

// Unverified on Windows. An exited process opens while any handle to it is still open, so its exit code decides; one
// this user may not open is there.
internal actual fun isProcessRunning(pid: Long): Boolean {
    if (pid > UInt.MAX_VALUE.toLong()) return false
    val process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION.toUInt(), FALSE, pid.toUInt())
        ?: return GetLastError() == ERROR_ACCESS_DENIED.toUInt()
    return try {
        memScoped {
            val code = alloc<DWORDVar>()
            GetExitCodeProcess(process, code.ptr) == 0 || code.value == STILL_ACTIVE
        }
    } finally {
        CloseHandle(process)
    }
}
