@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.EINTR
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.open
import platform.posix.strerror
import platform.posix.write

private const val FILE_MODE = 420 // 0644

internal actual fun openAppendFile(path: Path): AppendFile {
    val opened = open(path.toString(), O_WRONLY or O_APPEND or O_CREAT or O_CLOEXEC, FILE_MODE)
    if (opened < 0) throw IOException("$path: ${errnoReason()}")
    val descriptor = aboveStandardStreams(opened)
    return object : AppendFile {
        override fun append(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            bytes.usePinned { pinned ->
                var written = 0
                // A regular file takes the whole write at once; the loop only covers an interrupted or short one.
                while (written < bytes.size) {
                    val result = write(descriptor, pinned.addressOf(written), (bytes.size - written).convert())
                    when {
                        result >= 0 -> written += result.toInt()
                        errno != EINTR -> throw IOException("$path: ${errnoReason()}")
                    }
                }
            }
        }

        override fun close() {
            close(descriptor)
        }
    }
}

/**
 * Moves [descriptor] above 0, 1 and 2. With standard output or error closed, `open` hands the log file that
 * number, and every console line would land in the file.
 */
private fun aboveStandardStreams(descriptor: Int): Int {
    if (descriptor > STDERR_FILENO) return descriptor

    val moved = fcntl(descriptor, F_DUPFD_CLOEXEC, STDERR_FILENO + 1)
    close(descriptor)
    if (moved < 0) throw IOException("could not move the log file off a standard stream: ${errnoReason()}")
    return moved
}

private fun errnoReason(): String = strerror(errno)?.toKString() ?: "errno $errno"
