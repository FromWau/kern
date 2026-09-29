package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.TimeSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** How long [withLock] waits for another holder unless its caller says otherwise. */
public const val DEFAULT_LOCK_WAIT_MILLIS: Long = 500

/** How long a wait sleeps between attempts. */
internal const val LOCK_RETRY_INTERVAL_MILLIS: Long = 10

/**
 * Runs [block] holding an exclusive lock on this file, so it never runs at the same time as another [withLock]
 * on the same file, from another thread of this process or from another process, JVM and native alike.
 *
 * ```kotlin
 * (dirs.state / ".mirror.lock").withLock { runMirror() }
 * ```
 *
 * Lock a file nothing else opens, a sibling `.lock` rather than the file the work is about: closing any
 * descriptor to a file drops this process's locks on it, so work that opens and closes the locked file itself
 * would let the next holder in early. The file and any missing parent folders are created, and both stay
 * afterwards. Do not delete the lock file to tidy up, even when it looks stale: a holder that opens the file
 * after the deletion locks a new one and runs at the same time as the holder still inside.
 *
 * The lock is advisory: it keeps out other [withLock] calls, and nothing that ignores it. Waiting is bounded.
 * A holder that does not let go within [waitMillis] is [FileError.LockBusy], a [waitMillis] of zero tries once,
 * and a second [withLock] on the same file from inside [block] waits for itself and ends the same way. So does
 * a wait on a JVM thread that is interrupted, which stops waiting and leaves the thread's interrupt set.
 *
 * @param waitMillis how long to wait for a holder before giving up.
 * @param block the work, which runs on the calling thread. What it throws is not caught.
 * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time,
 *   [FileError.LockFailed] when it could not be attempted, or the error creating the parent folders gave.
 */
public fun <T> Path.withLock(
    waitMillis: Long = DEFAULT_LOCK_WAIT_MILLIS,
    block: () -> T,
): Result<T, FileError> {
    parent?.createDirectories()?.let { if (it is Result.Error) return it }

    val gate = gateName()
    val started = TimeSource.Monotonic.markNow()
    val waited = enterGateWithin(gate, waitMillis)
        ?: return Err(FileError.LockBusy(this, holderPid = null, waitedMs = started.elapsedNow().inWholeMilliseconds))
    return try {
        when (val outcome = withOsFileLock(this, waitMillis - waited, block)) {
            is OsLock.Ran -> Ok(outcome.value)
            // The wait belongs to the acquisition rather than to either half of it, so it is read once both
            // halves are over: a refusal costs the poll that noticed it on top of the budget it was given.
            is OsLock.Busy -> Err(FileError.LockBusy(this, outcome.holderPid, started.elapsedNow().inWholeMilliseconds))
            is OsLock.Failed -> Err(FileError.LockFailed(this, outcome.reason))
        }
    } finally {
        leaveGate(gate)
    }
}

/** What taking the OS lock came to. */
internal sealed interface OsLock<out T> {
    data class Ran<T>(val value: T) : OsLock<T>

    /** Another process held it for the whole wait. Only the posix actual can name the [holderPid]. */
    data class Busy(val holderPid: Int?) : OsLock<Nothing>

    data class Failed(val reason: String) : OsLock<Nothing>
}

/**
 * Takes the OS lock on [lockFile] within [timeoutMillis] and runs [block] under it. No thread of this process
 * is contending by the time this is called, so the only holder left to wait out is another process.
 */
internal expect fun <T> withOsFileLock(
    lockFile: Path,
    timeoutMillis: Long,
    block: () -> T,
): OsLock<T>

/** Blocks the calling thread, and answers false when it was interrupted before the time was up. */
internal expect fun sleepMillis(millis: Long): Boolean

/**
 * One name for the file this path reaches, however it was spelled, so `dir/.app.lock`, `dir/./.app.lock` and a
 * symlink to either cannot pass the gate side by side. The OS lock would not stop them: fcntl hands this
 * process the lock it already holds.
 */
private fun Path.gateName(): String = try {
    val resolved = if (SystemFileSystem.exists(this)) {
        SystemFileSystem.resolve(this)
    } else {
        Path(SystemFileSystem.resolve(parent ?: Path(".")), name)
    }
    resolved.toString()
} catch (_: Exception) {
    toString()
}

/**
 * The lock files this process is inside. An OS lock does not tell the threads of one process apart: fcntl
 * hands a process the lock it already holds, and a JVM channel refuses the second thread outright instead of
 * queueing it, so without this gate two holders in one process would both run. Nothing in common code holds a
 * lock table, so the held paths are one set swapped atomically.
 */
@OptIn(ExperimentalAtomicApi::class)
private val heldLockFiles = AtomicReference<Set<String>>(emptySet())

/**
 * How long entering the gate took, or null when [timeoutMillis] ran out, or the wait was interrupted, while
 * another thread was inside.
 */
private fun enterGateWithin(gate: String, timeoutMillis: Long): Long? {
    var waited = 0L
    while (!tryEnterGate(gate)) {
        if (waited >= timeoutMillis || !sleepMillis(LOCK_RETRY_INTERVAL_MILLIS)) return null
        waited += LOCK_RETRY_INTERVAL_MILLIS
    }
    return waited
}

@OptIn(ExperimentalAtomicApi::class)
private fun tryEnterGate(gate: String): Boolean {
    while (true) {
        val held = heldLockFiles.load()
        if (gate in held) return false
        if (heldLockFiles.compareAndSet(held, held + gate)) return true
    }
}

@OptIn(ExperimentalAtomicApi::class)
private fun leaveGate(gate: String) {
    heldLockFiles.update { it - gate }
}
