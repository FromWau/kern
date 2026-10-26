package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.update
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** How long [withLock] waits for another holder unless its caller says otherwise. */
public val DEFAULT_LOCK_WAIT: Duration = 500.milliseconds

/**
 * What [attempt] answers once it is anything but [FileError.LockBusy], trying again after each of the [lockPauses] of
 * [wait], which [pause] waits out, answering false to stop early; then the last [FileError.LockBusy], whose `waited` is
 * the sum of the pauses taken. For kern's own lock waits, blocking and suspending alike.
 */
@InternalKernApi
public inline fun <L> retryWhileLockBusy(
    wait: Duration,
    pause: (Duration) -> Boolean,
    attempt: () -> Result<L, FileError>,
): Result<L, FileError> {
    var waited = Duration.ZERO
    val pauses = lockPauses(wait).iterator()
    while (true) {
        val answer = attempt()
        val busy = answer.errorOrNull() as? FileError.LockBusy ?: return answer
        if (!pauses.hasNext()) return Err(busy.copy(waited = waited))
        val next = pauses.next()
        if (!pause(next)) return Err(busy.copy(waited = waited))
        waited += next
    }
}

/**
 * The pauses a lock wait of [wait] makes between attempts, as [withLock] describes them. None for a [wait] of zero or
 * less, and no end for [Duration.INFINITE].
 */
@PublishedApi
internal fun lockPauses(wait: Duration): Sequence<Duration> = sequence {
    var total = Duration.ZERO
    var next = FIRST_LOCK_PAUSE
    while (total < wait) {
        val pause = minOf(next, wait - total)
        yield(pause)
        total += pause
        next = minOf(next * 2, LONGEST_LOCK_PAUSE)
    }
}

private val FIRST_LOCK_PAUSE = 10.milliseconds

private val LONGEST_LOCK_PAUSE = 500.milliseconds

/**
 * Runs [block] holding an exclusive lock on this file, so it never runs at the same time as another [withLock]
 * on the same file, from another thread of this process or from another process, JVM and native alike.
 *
 * ```kotlin
 * (dirs.state / ".mirror.lock").withLock { runMirror() }.onError { println("mirror not run: $it") }
 * ```
 *
 * Lock a file nothing else opens, a sibling `.lock` rather than the file the work is about: closing any
 * descriptor to a file drops this process's locks on it, so work that opens and closes the locked file itself
 * would let the next holder in early. The file and any missing parent folders are created, and both stay
 * afterwards. Do not delete the lock file to tidy up, even when it looks stale: a holder that opens the file
 * after the deletion locks a new one and runs at the same time as the holder still inside.
 *
 * The lock is advisory: it keeps out other [withLock] calls, and nothing that ignores it. Waiting is bounded.
 * A holder that does not let go within [wait] is [FileError.LockBusy], and a [wait] of zero or less tries once, as
 * with `delay` and `withTimeout`. Between attempts the wait pauses 10 ms at first, twice as long each time after, at
 * most 500 ms, and the last pause is cut short so that together they come to [wait]. A wait on a JVM thread that is
 * interrupted stops and ends as [FileError.LockBusy] too, leaving the thread's interrupt set. A second [withLock] on
 * the same file from inside [block] waits for itself and ends the same way, naming this process as the holder: the
 * lock is not reentrant.
 *
 * @param wait how long to wait for a holder before giving up.
 * @param block the work, which runs on the calling thread and may call suspending functions when the caller can.
 *   What it throws is rethrown once the lock is released, also when it is cancelled. Waiting for the lock blocks the
 *   thread; `awaitLock` from kern:fs-coroutines waits by suspending.
 * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time, its `waited` the sum of the
 *   pauses; [FileError.NotRegularFile] when a folder, FIFO, device or socket is at this path, [FileError.LockFailed]
 *   when it could not be attempted, or the error creating the parent folders gave.
 */
public inline fun <T> Path.withLock(
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: () -> T,
): Result<T, FileError> = acquireLock(wait).map { held -> held.use { block() } }

/**
 * Takes the lock [withLock] holds, waiting up to [wait] for another holder, with a lock file that would not open or be
 * created reported as [unopened] says.
 */
@OptIn(InternalKernApi::class)
@PublishedApi
internal fun Path.acquireLock(
    wait: Duration,
    unopened: (lockFile: Path, failure: Exception) -> FileError = ::lockFailed,
): Result<FileLock, FileError> {
    val gate = when (val prepared = lockGate()) {
        is Result.Success -> prepared.value
        is Result.Error -> return prepared
    }
    return retryWhileLockBusy(wait, pause = { sleepMillis(it.inWholeMilliseconds) }) {
        attemptLock(gate, unopened) { tryOsLock(this) }
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
 * @return the held lock; [FileError.LockBusy] with a `waited` of zero when another holder, in this process or
 *   another, has it; [FileError.NotRegularFile] when a folder, FIFO, device or socket is at this path;
 *   [FileError.LockFailed] when it could not be attempted, or the error creating the parent folders gave.
 */
public fun Path.tryLock(): Result<FileLock, FileError> =
    lockGate().flatMap { gate -> attemptLock(gate, unopened = ::lockFailed) { tryOsLock(this) } }

/** A lock [tryLock] took on [path]. Closing it releases the lock; closing it again does nothing. */
@OptIn(ExperimentalAtomicApi::class)
public class FileLock internal constructor(
    public val path: Path,
    private val lockedKey: Any?,
    private val shown: (FileError) -> FileError,
    private val release: () -> Unit,
) : AutoCloseable {
    private val open = AtomicBoolean(true)

    /**
     * Whether [path] still names the file this lock holds. A lock file deleted, as `rm -rf` of its folder does, or
     * replaced by another, lets the next holder lock a new file there and run beside this one, so a holder that runs
     * long can check now and then and stop once the lock is lost.
     *
     * ```kotlin
     * lock.check().onError { failure -> if (failure is FileError.LockLost) return stopServing() }
     * ```
     *
     * Symlinks at [path] are followed, as taking the lock follows them. The JVM on Windows has no file keys to tell one
     * file from another, so there it answers `Ok`; on Windows native it compares the file's volume and index, which is
     * unverified. Once the lock is closed, it answers `Ok`.
     *
     * @return `Ok` while [path] names the locked file; [FileError.LockLost] when nothing is there or another file is;
     *   or the error looking [path] up gives when it cannot be looked up for another reason, such as
     *   [FileError.Inaccessible].
     */
    public fun check(): EmptyResult<FileError> {
        val locked = lockedKey
        if (locked == null || !open.load()) return Ok(Unit)
        val found = fileKeyOrNull(path)
        if (found == locked) return Ok(Unit)
        if (found != null) return Err(shown(FileError.LockLost(path)))
        val failure = path.metadata().errorOrNull()
        return Err(shown(if (failure == null || failure is FileError.NotFound) FileError.LockLost(path) else failure))
    }

    override fun close() {
        if (open.compareAndSet(expectedValue = true, newValue = false)) release()
    }
}

/** Deletes this lock's file, then lets go of the lock, for a claim whose file has no use once it is released. */
internal fun FileLock.deleteFileAndRelease() {
    // Only the holder deletes a lock file: a claimant that opened it before now finds it held, not free.
    val _ = path.delete()
    close()
}

/**
 * Refuses anything at this path but a regular file, creates the lock file's missing folders and names its in-process
 * [gate][gateName], once ahead of every attempt.
 */
internal fun Path.lockGate(): Result<String, FileError> {
    refusedPath()?.let { return it }
    // Looked up first, since the OS lock takes a FIFO without complaint and refuses a folder without naming it one.
    val found = metadata().getOrNull()
    if (found != null && !found.isRegularFile) return Err(FileError.NotRegularFile(this, typeOf(found)))
    val created = parent?.let { makeFolders(it) }
    if (created is Result.Error) return created
    return Ok(gateName())
}

/**
 * One try at the lock on this file, entering this process's [gate] first and leaving it again unless held, the OS lock
 * tried by [osLock].
 */
internal fun Path.attemptLock(
    gate: String,
    unopened: (lockFile: Path, failure: Exception) -> FileError,
    shown: (FileError) -> FileError = { it },
    osLock: () -> OsLock,
): Result<FileLock, FileError> {
    if (!tryEnterGate(gate)) return Err(FileError.LockBusy(this, currentProcessId(), waited = Duration.ZERO))
    val os = try {
        osLock()
    } catch (e: Exception) {
        OsLock.Failed(e)
    }
    if (os !is OsLock.Held) leaveGate(gate)
    return when (os) {
        is OsLock.Held -> Ok(
            FileLock(this, os.key, shown) {
                try {
                    os.release()
                } finally {
                    leaveGate(gate)
                }
            },
        )
        is OsLock.Busy -> Err(FileError.LockBusy(this, os.holderPid, waited = Duration.ZERO))
        is OsLock.NotOpened -> Err(unopened(this, os.failure))
        is OsLock.Failed -> Err(lockFailed(this, os.failure))
    }
}

internal fun lockFailed(
    lockFile: Path,
    failure: Exception,
): FileError = FileError.LockFailed(lockFile, failure.causeAt(lockFile), failure)

/** What one try at the OS lock came to. */
internal sealed interface OsLock {
    /**
     * Taken; [release] closes the descriptor, which is what lets the lock go. [key] tells the locked file from
     * another put at its path later, or is null where the platform cannot tell.
     */
    class Held(
        val key: Any?,
        val release: () -> Unit,
    ) : OsLock

    /** Something else holds it; [holderPid] names the holder where the OS can say who. */
    data class Busy(val holderPid: Long?) : OsLock

    /** The lock file would not open, or could not be created. */
    data class NotOpened(val failure: Exception) : OsLock

    /** The OS refused the lock call itself. */
    data class Failed(val failure: Exception) : OsLock
}

/**
 * Opens [lockFile] and tries its OS lock once, closing the descriptor again unless the lock was taken. No thread
 * of this process is contending by the time this is called, so the only holder left to meet is another process. With
 * [permissions], the lock file is created no wider than them and given them, where the platform has POSIX permissions;
 * without, a lock file the open creates asks for [newMode], which the umask narrows.
 */
internal expect fun tryOsLock(
    lockFile: Path,
    permissions: Int? = null,
    newMode: Int = LOCK_FILE_MODE,
): OsLock

/**
 * What tells the file at [path] from any other, following symlinks, as [OsLock.Held.key] records it for a lock: null
 * when nothing is there, it cannot be looked up, or the platform cannot tell files apart.
 */
internal expect fun fileKeyOrNull(path: Path): Any?

/** This process's id as the OS knows it, the one [FileError.LockBusy.holderPid] names a holder by. */
public expect fun currentProcessId(): Long

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
        Path(SystemFileSystem.resolve(folderOrDot()), name)
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
