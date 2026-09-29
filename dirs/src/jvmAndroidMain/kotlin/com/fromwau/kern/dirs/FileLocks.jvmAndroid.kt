package com.fromwau.kern.dirs

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import kotlinx.io.files.Path

/**
 * `FileChannel` locks are fcntl-based on Linux, the same primitive the posix actual uses, so a JVM holder and
 * a native one contend with each other rather than passing straight through.
 *
 * `tryLock` in a bounded retry, not the blocking `lock()`: [withLock] promises a bounded wait, and
 * `lock()` waits for a holder forever. A guarantee that holds on only one runtime is worse than none.
 *
 * The descriptor is opened and closed inside the caller's in-process gate, which is load-bearing rather than
 * tidy. Closing any descriptor to a file drops every lock this process holds on it, so a descriptor opened
 * before the gate and closed after it would destroy another thread's lock while that thread was still inside
 * its critical section, and silently, since `FileLock.isValid` stays true.
 */
internal actual fun <T> withOsFileLock(
    lockFile: Path,
    timeoutMillis: Long,
    block: () -> T,
): OsLock<T> {
    val opened = try {
        RandomAccessFile(File(lockFile.toString()), "rw")
    } catch (e: Exception) {
        return OsLock.Failed(e.message ?: "could not open the lock file")
    }
    return opened.use { handle -> lockAndRun(handle.channel, timeoutMillis, block) }
}

// Thread.sleep clears the interrupt as it throws, so it is set again for whoever asked this thread to stop.
internal actual fun sleepMillis(millis: Long): Boolean = try {
    Thread.sleep(millis)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}

private fun <T> lockAndRun(channel: FileChannel, timeoutMillis: Long, block: () -> T): OsLock<T> =
    when (val outcome = acquireWithin(channel, timeoutMillis)) {
        is LockOutcome.Acquired -> outcome.lock.use { OsLock.Ran(block()) }
        // java.nio exposes no F_GETLK, so the holder cannot be named here as it can natively.
        is LockOutcome.Busy -> OsLock.Busy(holderPid = null)
        is LockOutcome.Failed -> OsLock.Failed(outcome.detail)
    }

/**
 * Polls for the lock until [timeoutMillis] has passed or the wait is interrupted. A null `tryLock` means another
 * process holds it.
 */
private fun acquireWithin(channel: FileChannel, timeoutMillis: Long): LockOutcome {
    var waited = 0L
    var outcome: LockOutcome? = null
    while (outcome == null) {
        outcome = try {
            channel.tryLock()?.let { LockOutcome.Acquired(it) } ?: LockOutcome.Busy.takeIf { waited >= timeoutMillis }
        } catch (e: Exception) {
            LockOutcome.Failed(e.message ?: "could not take the lock")
        }
        if (outcome == null) {
            if (!sleepMillis(LOCK_RETRY_INTERVAL_MILLIS)) outcome = LockOutcome.Busy
            waited += LOCK_RETRY_INTERVAL_MILLIS
        }
    }
    return outcome
}

private sealed interface LockOutcome {
    data class Acquired(val lock: FileLock) : LockOutcome
    data object Busy : LockOutcome
    data class Failed(val detail: String) : LockOutcome
}
