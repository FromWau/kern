package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.DEFAULT_LOCK_WAIT_MILLIS
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.tryLock
import com.fromwau.kern.fs.withLock
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.delay
import kotlinx.io.files.Path

/** How long a wait suspends between attempts. */
private const val RETRY_INTERVAL_MILLIS: Long = 10

/**
 * Runs [block] holding the lock [withLock] takes on this file, waiting for another holder by suspending rather
 * than blocking the thread. Each attempt opens and locks the file with a little blocking I/O on the calling
 * thread; the wait between attempts blocks nothing.
 *
 * ```kotlin
 * (dirs.state / ".sync.lock").awaitLock { syncNow() }
 * ```
 *
 * The rules of [withLock] apply: lock a file nothing else opens, and leave it in place afterwards. The lock is
 * released when [block] returns, throws, or is cancelled, and a wait that is cancelled holds nothing.
 *
 * @param waitMillis how long to wait for a holder before giving up.
 * @param block the work, which may suspend and resume on another thread while the lock is held.
 * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time, which never names the
 *   holder and whose `waitedMs` counts the pauses between attempts; [FileError.LockFailed] when it could not be
 *   attempted, or the error creating the parent folders gave.
 */
public suspend fun <T> Path.awaitLock(
    waitMillis: Long = DEFAULT_LOCK_WAIT_MILLIS,
    block: suspend () -> T,
): Result<T, FileError> {
    var waited = 0L
    while (true) {
        when (val attempt = tryLock()) {
            is Result.Error -> return attempt
            is Result.Success -> attempt.value?.let { lock -> return lock.use { Ok(block()) } }
        }
        if (waited >= waitMillis) return Err(FileError.LockBusy(this, holderPid = null, waitedMs = waited))
        delay(RETRY_INTERVAL_MILLIS)
        waited += RETRY_INTERVAL_MILLIS
    }
}
