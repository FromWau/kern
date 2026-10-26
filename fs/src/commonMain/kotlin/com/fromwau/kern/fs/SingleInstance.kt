package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.io.files.Path

/**
 * Keeps a second run of this app named [name] out for as long as you hold the answer: [Writer.tryLock] on
 * `state / ".<name>.lock"`, then this process's pid and a `\n` written to `state / "<name>.pid"`, readable by you
 * alone, so a run that finds the lock busy can say who holds it.
 *
 * ```kotlin
 * val instance = dirs.singleInstance("mirror").getOrElse { failure ->
 *     val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
 *     return println("mirror already runs as pid $pid")
 * }
 * instance.use { runMirror() }
 * ```
 *
 * Both files are made through a private [Writer] on `state`, so a missing `state` is made usable by you alone, `700`,
 * the missing folders above it as [writer] makes them, and one already there keeps its permissions; one others may
 * read is taken, as [privateWriter] would not. Before anything is locked or written, a `state` someone else owns, or
 * one the group or others may write, is [FileError.Refused], since they could replace the lock file; Windows says
 * neither, so any `state` passes there. Each file is made `600`, and a symlink at either name is replaced by the file,
 * never followed. Both files stay once the lock is released, as every lock file does. The pid file can be stale: a
 * holder that was killed leaves its pid behind, and a run that just took the lock has not written its own yet. So a
 * pid is named only while a process with it is running, and a pid file naming none is read again for up to 100 ms,
 * which is how long the busy answer can take. A running pid is no proof: one a killed holder left can since belong to
 * an unrelated process, which is then named. Call [InstanceLock.check] to learn whether the lock file was since
 * deleted or replaced.
 *
 * @param name the app's or the task's name, one name in a folder as [nameProblem] checks it, of at most 249 bytes in
 *   UTF-8, so the lock file's name `.<name>.lock` takes at most 255.
 * @return the held lock; [FileError.LockBusy] when another holder has it, naming as its `holderPid` the holder the
 *   lock names, or else the pid in the pid file when a process with it is running, or null, and a `waited` of zero
 *   whatever the reading took; [FileError.InvalidName] naming the lock file when [name] is not one name, with
 *   [NameProblem.TooLong] when the lock file's name would take more than 255 bytes;
 *   [FileError.Refused] naming `state`'s real path, with what it found there and [FileError.Refused.why], when `state`
 *   is not yours alone to write; or what making `state`, [Writer.tryLock] or writing the pid file answers, the lock
 *   released again after a failed write.
 */
@OptIn(InternalKernApi::class)
public fun AppDirs.singleInstance(name: String): Result<InstanceLock, FileError> =
    singleInstanceAttempt(name).mapError { failure ->
        namingInstanceHolder(name, failure, pause = { val _ = sleepMillis(it.inWholeMilliseconds) })
    }

/**
 * [singleInstance] with a [FileError.LockBusy] naming only the holder the lock names, for kern's own waits, which
 * name one with [namingInstanceHolder] once they give up.
 */
@InternalKernApi
public fun AppDirs.singleInstanceAttempt(name: String): Result<InstanceLock, FileError> {
    val lockName = lockFileName(name)
    // The pid file's name is shorter, so a name whose lock file's name passes fits both.
    (name.nameProblem() ?: lockName.nameProblem())?.let { return Err(FileError.InvalidName(state / lockName, it)) }
    val folder = state.newWriter(RootRule.Trusted, create = true).getOrElse { return Err(it) }
    val lock = folder.tryLock(lockName).getOrElse { return Err(it) }
    val written = folder.writeText(pidFileName(name), "${currentProcessId()}\n", replaceLink = true)
    if (written is Result.Error) {
        lock.close()
        return written
    }
    return Ok(InstanceLock(folder.root / lockName, folder.root / pidFileName(name), lock))
}

/**
 * [failure], with the pid in the pid file of the instance [name] as its holder when it is a [FileError.LockBusy]
 * naming none, as [singleInstance] names it; [pause] waits between the reads.
 */
@InternalKernApi
public inline fun AppDirs.namingInstanceHolder(
    name: String,
    failure: FileError,
    pause: (Duration) -> Unit,
): FileError {
    if (failure !is FileError.LockBusy || failure.holderPid != null) return failure
    return failure.copy(holderPid = runningPidIn(pidFileOf(name), pause))
}

/**
 * [singleInstance] for work keyed by a path rather than a name, such as a build into one output folder: two runs given
 * one folder, however each spells it, keep each other out, while runs for two folders do not.
 *
 * ```kotlin
 * val instance = dirs.singleInstance(outputFolder).getOrElse { return report(it) }
 * instance.use { build(into = outputFolder) }
 * ```
 *
 * [key] is resolved as [canonicalPath] resolves it, so `out`, `src/../out/` and a link to `out` are one key, also
 * before `out` exists. The instance is then [singleInstance] of the name `<last name>-<hash>`: the last name of the
 * resolved path, then 16 lowercase hex digits of the FNV-1a 64-bit hash of the resolved path's UTF-8 bytes, so
 * `/srv/site/out` is `out-4c2894b7bcda536c`. A `/` or `\` in the last name becomes `_`, and a last name too long for
 * the lock file's name is cut to its first 232 bytes in UTF-8, never inside a character, so the name is always valid.
 * Each key leaves its own lock file and pid file in `state`.
 *
 * @return what [singleInstance] answers for that name, or what [canonicalPath] answers for a [key] it cannot resolve.
 */
@OptIn(InternalKernApi::class)
public fun AppDirs.singleInstance(key: Path): Result<InstanceLock, FileError> =
    key.instanceName().flatMap { singleInstance(it) }

/** The name [singleInstance] with a key path takes the instance of for this key, or why the key cannot be resolved. */
@InternalKernApi
public fun Path.instanceName(): Result<String, FileError> = canonicalPath().map(::instanceNameOf)

/** The instance name of a key that resolved to [resolved], as [singleInstance] with a key path names it. */
internal fun instanceNameOf(resolved: Path): String {
    val hash = fnv1a64(resolved.toString().encodeToByteArray()).toString(radix = 16).padStart(HASH_DIGITS, '0')
    val lastName = resolved.name
        .replace('/', '_')
        .replace('\\', '_')
        .takeUtf8Bytes(LONGEST_KEY_NAME_BYTES)
    return keyedName(lastName, hash)
}

private fun keyedName(
    lastName: String,
    hash: String,
): String = "$lastName-$hash"

/** The 64-bit FNV-1a hash of [bytes]. */
private fun fnv1a64(bytes: ByteArray): ULong = bytes.fold(FNV_OFFSET_BASIS) { hash, byte ->
    (hash xor byte.toUByte().toULong()) * FNV_PRIME
}

/** The longest start of this string that takes at most [limit] bytes in UTF-8, never cutting a character in two. */
private fun String.takeUtf8Bytes(limit: Int): String {
    var bytes = 0
    var end = 0
    while (end < length) {
        val step = if (this[end].isHighSurrogate() && end + 1 < length) 2 else 1
        val size = substring(end, end + step).encodeToByteArray().size
        if (bytes + size > limit) break
        bytes += size
        end += step
    }
    return substring(0, end)
}

private const val FNV_OFFSET_BASIS: ULong = 0xcbf29ce484222325uL

private const val FNV_PRIME: ULong = 0x100000001b3uL

private const val HASH_DIGITS = 16

/** What is left of a lock file's 255 bytes for the last name, once the hash is added and the lock file named. */
private val LONGEST_KEY_NAME_BYTES =
    LONGEST_NAME_BYTES - lockFileName(keyedName("", "0".repeat(HASH_DIGITS))).encodeToByteArray().size

/** The name of the lock file [singleInstance] takes for the instance [name], beside its [pidFileOf]. */
private fun lockFileName(name: String): String = ".$name.lock"

/** The name of the file [singleInstance] writes the holder's pid to for the instance [name]. */
private fun pidFileName(name: String): String = "$name.pid"

@PublishedApi
internal fun AppDirs.pidFileOf(name: String): Path = state / pidFileName(name)

/**
 * A lock [singleInstance] holds on [lockFile], with this process's pid in [pidFile], both named by the real path of the
 * `state` folder they are in. Closing it releases the lock and leaves both files; closing it again does nothing.
 */
public class InstanceLock internal constructor(
    public val lockFile: Path,
    public val pidFile: Path,
    private val lock: FileLock,
) : AutoCloseable {
    /** Whether the lock file is still the file this lock holds, as [FileLock.check] answers for [lockFile]. */
    public fun check(): EmptyResult<FileError> = lock.check()

    override fun close(): Unit = lock.close()
}

/**
 * The pid in [pidFile] when a process with it is running, read again after each [pause] for up to [PID_REREAD_WAIT],
 * since a holder that just took the lock writes its pid a moment later; or null when none turns up.
 */
@PublishedApi
internal inline fun runningPidIn(
    pidFile: Path,
    pause: (Duration) -> Unit,
): Long? {
    var waited = Duration.ZERO
    while (true) {
        runningPidOrNull(pidFile)?.let { return it }
        if (waited >= PID_REREAD_WAIT) return null
        pause(PID_REREAD_PAUSE)
        waited += PID_REREAD_PAUSE
    }
}

/** The pid in [pidFile] when a process with it is running now. */
@PublishedApi
internal fun runningPidOrNull(pidFile: Path): Long? = pidFile.readPid()?.takeIf(::isProcessRunning)

private fun Path.readPid(): Long? = readText(maxBytes = LONGEST_PID_FILE)
    .getOrNull()
    ?.trim()
    ?.toLongOrNull()
    ?.takeIf { it > 0 }

/** Whether a process with this pid, which is above zero, is running now, as far as this user can tell. */
internal expect fun isProcessRunning(pid: Long): Boolean

/** More than any pid takes in decimal, with room for a line break. */
private const val LONGEST_PID_FILE = 32L

@PublishedApi
internal val PID_REREAD_WAIT: Duration = 100.milliseconds

@PublishedApi
internal val PID_REREAD_PAUSE: Duration = 10.milliseconds
