package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.windows.CONSOLE_SCREEN_BUFFER_INFO
import platform.windows.CP_UTF8
import platform.windows.DWORDVar
import platform.windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING
import platform.windows.GetConsoleMode
import platform.windows.GetConsoleScreenBufferInfo
import platform.windows.GetStdHandle
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.STD_ERROR_HANDLE
import platform.windows.STD_OUTPUT_HANDLE
import platform.windows.SetConsoleMode
import platform.windows.SetConsoleOutputCP

// Both console steps must land before the first byte is written, so they run unconditionally here rather
// than inside a probe the color ladder can skip.
internal actual fun platformIo(): PlatformIo =
    nativePlatformIo(width = configureConsoleAndDetectWidth(), ansiCapable = enableVirtualTerminal())

// The UTF-8 opt-in rides along because it needs the attached-console handle this loop already finds;
// without it non-ASCII text is reinterpreted under the box's legacy code page and mojibaked on screen.
@OptIn(ExperimentalForeignApi::class)
private fun configureConsoleAndDetectWidth(): Int? = memScoped {
    val info = alloc<CONSOLE_SCREEN_BUFFER_INFO>()
    // A redirected/piped stdout fails the console query; try stderr too before giving up.
    for (stream in listOf(STD_OUTPUT_HANDLE, STD_ERROR_HANDLE)) {
        val handle = GetStdHandle(stream)
        if (handle != null && handle != INVALID_HANDLE_VALUE &&
            GetConsoleScreenBufferInfo(handle, info.ptr) != 0
        ) {
            SetConsoleOutputCP(CP_UTF8.convert())
            val width = info.srWindow.Right - info.srWindow.Left + 1
            if (width > 0) return@memScoped width
        }
    }
    null
}

// The classic console host renders escape bytes literally until VT processing is opted into per-handle.
@OptIn(ExperimentalForeignApi::class)
private fun enableVirtualTerminal(): Boolean = memScoped {
    val handle = GetStdHandle(STD_OUTPUT_HANDLE)
    if (handle == null || handle == INVALID_HANDLE_VALUE) return@memScoped false
    val mode = alloc<DWORDVar>()
    if (GetConsoleMode(handle, mode.ptr) == 0) return@memScoped false
    val vtFlag: UInt = ENABLE_VIRTUAL_TERMINAL_PROCESSING.convert()
    if ((mode.value and vtFlag) != 0u) return@memScoped true
    SetConsoleMode(handle, mode.value or vtFlag) != 0
}
