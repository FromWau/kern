package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.concurrent.atomics.AtomicBoolean
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
    val gate = when (val prepared = lockGate()) {
        is Result.Success -> prepared.value
        is Result.Error -> return prepared
    }
    val started = TimeSource.Monotonic.markNow()
    while (true) {
        when (val attempt = attemptLock(gate)) {
            is LockAttempt.Held -> return attempt.lock.use { Ok(block()) }
            is LockAttempt.Failed -> return Err(attempt.error)
            is LockAttempt.Busy -> {
                val waited = started.elapsedNow().inWholeMilliseconds
                if (waited >= waitMillis || !sleepMillis(LOCK_RETRY_INTERVAL_MILLIS)) {
                    return Err(FileError.LockBusy(this, attempt.holderPid, waited))
                }
            }
        }
    }
}

/**
 * Tries once for the lock [withLock] takes on this file, without waiting, and hands it over to hold for as long as
 * you need: until [FileLock.close], which a `use` block does for you.
 *
 * ```kotlin
 * val instance = (dirs.state / ".instance.lock").tryLock()   // held for the whole run of the app
 * ```
 *
 * The same rules as [withLock] apply: lock a file nothing else opens, and leave it in place afterwards. The lock
 * belongs to this process rather than to a thread, so it may be released from another thread than took it.
 *
 * @return the held lock; null when another holder, in this process or another, has it; [FileError.LockFailed]
 *   when it could not be attempted, or the error creating the parent folders gave.
 */
public fun Path.tryLock(): Result<FileLock?, FileError> {
    val gate = when (val prepared = lockGate()) {
        is Result.Success -> prepared.value
        is Result.Error -> return prepared
    }
    return when (val attempt = attemptLock(gate)) {
        is LockAttempt.Held -> Ok(attempt.lock)
        is LockAttempt.Busy -> Ok(null)
        is LockAttempt.Failed -> Err(attempt.error)
    }
}

/** A lock [tryLock] took on [path]. Closing it releases the lock; closing it again does nothing. */
@OptIn(ExperimentalAtomicApi::class)
public class FileLock internal constructor(
    public val path: Path,
    private val release: () -> Unit,
) : AutoCloseable {
    private val open = AtomicBoolean(true)

    override fun close() {
        if (open.compareAndSet(expectedValue = true, newValue = false)) release()
    }
}

/** What one try at a lock came to, keeping the holder [tryLock] has no room for. */
internal sealed interface LockAttempt {
    data class Held(val lock: FileLock) : LockAttempt

    /** Another holder has it. Only the posix actual can name the [holderPid] of another process. */
    data class Busy(val holderPid: Int?) : LockAttempt

    data class Failed(val error: FileError) : LockAttempt
}

/** Creates this lock file's missing folders and names its in-process [gate][gateName], once ahead of every attempt. */
private fun Path.lockGate(): Result<String, FileError> {
    val created = parent?.createDirectories()
    if (created is Result.Error) return created
    return Ok(gateName())
}

/** One try at the lock on this file, entering this process's [gate] first and leaving it again unless held. */
private fun Path.attemptLock(gate: String): LockAttempt {
    if (!tryEnterGate(gate)) return LockAttempt.Busy(holderPid = null)
    val os = try {
        tryOsLock(this)
    } catch (e: Exception) {
        OsLock.Failed(e.reason)
    }
    if (os !is OsLock.Held) leaveGate(gate)
    return when (os) {
        is OsLock.Held -> LockAttempt.Held(
            FileLock(this) {
                try {
                    os.release()
                } finally {
                    leaveGate(gate)
                }
            },
        )
        is OsLock.Busy -> LockAttempt.Busy(os.holderPid)
        is OsLock.Failed -> LockAttempt.Failed(FileError.LockFailed(this, os.reason))
    }
}

/** What one try at the OS lock came to. */
internal sealed interface OsLock {
    /** Taken; [release] closes the descriptor, which is what lets the lock go. */
    class Held(val release: () -> Unit) : OsLock

    data class Busy(val holderPid: Int?) : OsLock

    data class Failed(val reason: String) : OsLock
}

/**
 * Opens [lockFile] and tries its OS lock once, closing the descriptor again unless the lock was taken. No thread
 * of this process is contending by the time this is called, so the only holder left to meet is another process.
 */
internal expect fun tryOsLock(lockFile: Path): OsLock

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
