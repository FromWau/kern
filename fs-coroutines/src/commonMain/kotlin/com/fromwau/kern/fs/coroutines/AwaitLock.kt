package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.fs.DEFAULT_LOCK_WAIT
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InstanceLock
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.Writer
import com.fromwau.kern.fs.instanceName
import com.fromwau.kern.fs.namingInstanceHolder
import com.fromwau.kern.fs.retryWhileLockBusy
import com.fromwau.kern.fs.singleInstance
import com.fromwau.kern.fs.singleInstanceAttempt
import com.fromwau.kern.fs.tryLock
import com.fromwau.kern.fs.withLock
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.delay
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * Runs [block] holding the lock [withLock] takes on this file, waiting for another holder by suspending rather
 * than blocking the thread. Each attempt opens and locks the file with a little blocking I/O on the calling
 * thread; the wait between attempts blocks nothing.
 *
 * ```kotlin
 * (dirs.state / ".sync.lock").awaitLock { syncNow() }.onError { println("not synced: $it") }
 * ```
 *
 * The rules of [withLock] apply: lock a file nothing else opens, and leave it in place afterwards. The lock is
 * released when [block] returns, throws, or is cancelled, and what [block] throws is rethrown once it is released. A
 * wait that is cancelled holds nothing.
 *
 * @param wait how long to wait for a holder before giving up; zero or less tries once, as with `delay`. The pauses
 *   between attempts are those [withLock] describes.
 * @param block the work, which may suspend and resume on another thread while the lock is held.
 * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time, naming the holder as
 *   [tryLock] does, and whose `waited` is the sum of the pauses between attempts; [FileError.NotRegularFile] when a
 *   folder, FIFO, device or socket is at this path; [FileError.LockFailed] when it could not be attempted, or the error
 *   creating the parent folders gave.
 */
public suspend fun <T> Path.awaitLock(
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend () -> T,
): Result<T, FileError> = when (val held = whileBusy(wait) { tryLock() }) {
    is Result.Success -> held.value.use { Ok(block()) }
    is Result.Error -> held
}

/**
 * Runs [block] holding the lock [Writer.withLock] takes on the file [name] below this writer's root, waiting for
 * another holder by suspending rather than blocking the thread, as [awaitLock] on a path does.
 *
 * ```kotlin
 * state.awaitLock(".sync.lock") { syncNow() }.onError { println("not synced: $it") }
 * ```
 *
 * @param wait how long to wait for a holder before giving up; zero or less tries once, as with `delay`.
 * @param block the work, which may suspend and resume on another thread while the lock is held.
 * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time; or what [Writer.tryLock]
 *   answers, such as [FileError.InvalidRelativePath] or [FileError.Escapes], with [block] never run.
 */
public suspend fun <T> Writer.awaitLock(
    name: String,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend () -> T,
): Result<T, FileError> = when (val held = whileBusy(wait) { tryLock(name) }) {
    is Result.Success -> held.value.use { Ok(block()) }
    is Result.Error -> held
}

/**
 * Runs [block] as the single instance of this app named [name], holding what [singleInstance] takes, and waits for
 * another holder by suspending rather than blocking the thread, as [awaitLock] does.
 *
 * ```kotlin
 * dirs.awaitSingleInstance("mirror", wait = 30.seconds) { runMirror() }.onError { failure ->
 *     val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
 *     println("mirror still runs as pid $pid")
 * }
 * ```
 *
 * The rules of [singleInstance] apply: this process's pid is in the pid file while [block] runs, and both files stay
 * afterwards. Take that lock file with this or [singleInstance] alone, as "One instance of an app" in fs's locking
 * page explains. The instance is released when [block] returns, throws, or is cancelled, and what [block] throws is
 * rethrown once it is released. A wait that is cancelled holds nothing.
 *
 * @param name the app's or the task's name, one name in a folder, as [singleInstance] takes it.
 * @param wait how long to wait for another holder before giving up; zero or less tries once, as with `delay`.
 * @param block the work, which may suspend and resume on another thread while the instance is held. It is handed the
 *   [InstanceLock] it holds, so work that runs long can call [InstanceLock.check] to learn whether the lock file was
 *   since deleted or replaced.
 * @return what [block] returned; [FileError.LockBusy] when the instance was not free in time, naming the holder as
 *   [singleInstance] does and whose `waited` is the sum of the pauses between attempts, as [awaitLock] makes them;
 *   otherwise what [singleInstance] answers, such as [FileError.InvalidName] for a [name] that is not one name.
 */
@OptIn(InternalKernApi::class)
public suspend fun <T> AppDirs.awaitSingleInstance(
    name: String,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend (instance: InstanceLock) -> T,
): Result<T, FileError> = when (val held = whileBusy(wait) { singleInstanceAttempt(name) }) {
    is Result.Success -> held.value.use { Ok(block(it)) }
    is Result.Error -> Err(namingHolder(name, held.error))
}

/**
 * Runs [block] as the single instance of the work keyed by the path [key], as `singleInstance(key)` takes it, waiting
 * for another holder by suspending as [awaitLock] does. `out`, `src/../out/` and a link to `out` are one key.
 *
 * ```kotlin
 * dirs.awaitSingleInstance(outputFolder, wait = 30.seconds) { build(into = outputFolder) }.onError { println(it) }
 * ```
 *
 * @param key the path the work is about, resolved as `singleInstance(key)` resolves it, also before it exists.
 * @param wait how long to wait for another holder before giving up; zero or less tries once, as with `delay`.
 * @param block the work, handed the [InstanceLock] it holds, as the [awaitSingleInstance] taking a name hands it.
 * @return what the [awaitSingleInstance] taking a name answers for the key's instance name, or what resolving [key]
 *   answers when it cannot be resolved.
 */
@OptIn(InternalKernApi::class)
public suspend fun <T> AppDirs.awaitSingleInstance(
    key: Path,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend (instance: InstanceLock) -> T,
): Result<T, FileError> = when (val name = key.instanceName()) {
    is Result.Success -> awaitSingleInstance(name.value, wait, block)
    is Result.Error -> name
}

/** [failure] naming the holder of the instance [name] as [singleInstance] does, reading again by suspending. */
@OptIn(InternalKernApi::class)
internal suspend fun AppDirs.namingHolder(
    name: String,
    failure: FileError,
): FileError = namingInstanceHolder(name, failure, pause = { delay(it) })

/**
 * What [attempt] answers once it is anything but [FileError.LockBusy], or [wait] is up, suspending between attempts as
 * [withLock] sleeps.
 */
@OptIn(InternalKernApi::class)
private suspend fun <L> whileBusy(
    wait: Duration,
    attempt: () -> Result<L, FileError>,
): Result<L, FileError> = retryWhileLockBusy(
    wait,
    pause = {
        delay(it)
        true
    },
    attempt,
)
