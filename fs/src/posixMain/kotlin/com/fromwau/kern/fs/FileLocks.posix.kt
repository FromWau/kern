package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.EACCES
import platform.posix.EAGAIN
import platform.posix.F_GETLK
import platform.posix.F_SETLK
import platform.posix.F_UNLCK
import platform.posix.F_WRLCK
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.SEEK_SET
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.flock
import platform.posix.open
import platform.posix.usleep

private const val LOCK_FILE_MODE = 420 // 0644
private const val MICROS_PER_MILLI = 1000

/**
 * `fcntl`, the same primitive the JVM's `FileChannel.lock` uses, so a native holder and a JVM one contend with
 * each other. `flock` would not: it is a separate, non-interacting lock table on Linux, so a native `flock`
 * would silently ignore a JVM lock and both holders would proceed.
 *
 * `F_SETLK`, one try, rather than `F_SETLKW`: a blocking wait turns a live but stalled holder into a caller that
 * hangs with no output and no deadline. The caller retries within its own bound and names the holder instead.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun tryOsLock(lockFile: Path): OsLock {
    val descriptor = open(lockFile.toString(), O_RDWR or O_CREAT, LOCK_FILE_MODE)
    if (descriptor < 0) return OsLock.Failed(errnoReason())

    val outcome = memScoped {
        val request = alloc<flock>()
        request.requestWriteLock()
        when {
            fcntl(descriptor, F_SETLK, request.ptr) != -1 -> null
            // EACCES and EAGAIN mean another process holds it; anything else is a failure.
            errno != EACCES && errno != EAGAIN -> OsLock.Failed(errnoReason())
            else -> OsLock.Busy(holderPid(descriptor, request))
        }
    }
    if (outcome != null) {
        close(descriptor)
        return outcome
    }
    return OsLock.Held { close(descriptor) }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sleepMillis(millis: Long): Boolean {
    usleep((millis * MICROS_PER_MILLI).convert())
    return true
}

/** Whoever the kernel says holds the lock, or null when it went away between the attempt and the question. */
@OptIn(ExperimentalForeignApi::class)
private fun holderPid(descriptor: Int, request: flock): Int? {
    request.requestWriteLock()
    val answered = fcntl(descriptor, F_GETLK, request.ptr) != -1
    return if (answered && request.l_type.toInt() != F_UNLCK) request.l_pid else null
}

@OptIn(ExperimentalForeignApi::class)
private fun flock.requestWriteLock() {
    l_type = F_WRLCK.convert()
    l_whence = SEEK_SET.convert()
    l_start = 0
    l_len = 0 // 0 reaches end of file, so the whole file
}
