package com.fromwau.kern.dirs

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
 * `F_SETLK` in a bounded retry rather than `F_SETLKW`: a blocking wait turns a live but stalled holder into a
 * caller that hangs with no output and no deadline. Giving up names the holder instead.
 *
 * The descriptor is closed only after [block] returns, since closing it is what releases the lock.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withOsFileLock(
    lockFile: Path,
    timeoutMillis: Long,
    block: () -> T,
): OsLock<T> {
    val descriptor = open(lockFile.toString(), O_RDWR or O_CREAT, LOCK_FILE_MODE)
    if (descriptor < 0) return OsLock.Failed(errnoReason())

    return try {
        when (val outcome = acquireWithin(descriptor, timeoutMillis)) {
            is LockOutcome.Acquired -> OsLock.Ran(block())
            is LockOutcome.Busy -> OsLock.Busy(outcome.holderPid)
            is LockOutcome.Failed -> OsLock.Failed(outcome.detail)
        }
    } finally {
        close(descriptor)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sleepMillis(millis: Long): Boolean {
    usleep((millis * MICROS_PER_MILLI).convert())
    return true
}

private sealed interface LockOutcome {
    data object Acquired : LockOutcome
    data class Busy(val holderPid: Int?) : LockOutcome
    data class Failed(val detail: String) : LockOutcome
}

/** Polls for the lock until [timeoutMillis] has passed. EACCES and EAGAIN mean held; anything else is a failure. */
@OptIn(ExperimentalForeignApi::class)
private fun acquireWithin(descriptor: Int, timeoutMillis: Long): LockOutcome = memScoped {
    val request = alloc<flock>()
    var waited = 0L
    var outcome: LockOutcome? = null
    while (outcome == null) {
        request.requestWriteLock()
        outcome = when {
            fcntl(descriptor, F_SETLK, request.ptr) != -1 -> LockOutcome.Acquired
            errno != EACCES && errno != EAGAIN -> LockOutcome.Failed(errnoReason())
            waited >= timeoutMillis -> LockOutcome.Busy(holderPid(descriptor, request))
            else -> null
        }
        if (outcome == null) {
            sleepMillis(LOCK_RETRY_INTERVAL_MILLIS)
            waited += LOCK_RETRY_INTERVAL_MILLIS
        }
    }
    outcome
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
