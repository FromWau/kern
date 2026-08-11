package com.fromwau.kern.terminal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.TIOCGWINSZ
import platform.posix.ioctl
import platform.posix.winsize

// Byte-identical to the apple actual, and it has to stay that way: TIOCGWINSZ is typed differently per
// target, so the commonizer drops it from any shared native source set and this cannot be hoisted.
internal actual fun platformIo(): PlatformIo = nativePlatformIo(width = detectWidth(), ansiCapable = true)

@OptIn(ExperimentalForeignApi::class)
private fun detectWidth(): Int? = memScoped {
    val ws = alloc<winsize>()
    // A redirected/piped stdout fails ioctl; try stderr too before giving up.
    for (fd in intArrayOf(STDOUT_FILENO, STDERR_FILENO)) {
        if (ioctl(fd, TIOCGWINSZ.convert(), ws.ptr) == 0 && ws.ws_col.toInt() > 0) {
            return@memScoped ws.ws_col.toInt()
        }
    }
    null
}
