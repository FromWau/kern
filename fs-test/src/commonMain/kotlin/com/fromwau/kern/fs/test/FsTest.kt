package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.canonicalPath
import com.fromwau.kern.fs.createDirectories
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.of
import com.fromwau.kern.fs.pathError
import com.fromwau.kern.fs.setLastModified
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import kotlinx.io.files.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A new, empty folder of its own for a test, `kern-scratch-<uuid>` directly under the temp folder: `$TMPDIR` when it
 * names an absolute path, on every platform, and the platform's own temp folder otherwise. The path answered is the
 * temp folder's real one, with its links resolved, so it equals what `realPath()` gives for the scratch folder. It is
 * `755` whatever the umask, so a test that checks who may write a folder sees the same one on every machine. Nothing
 * removes it, so delete it with `deleteRecursively()` when the test is done.
 *
 * ```kotlin
 * val dir = scratchDir()
 * try {
 *     (dir / "app.toml").writeText("a = 1").assertSuccess()
 * } finally {
 *     dir.deleteRecursively().assertSuccess()
 * }
 * ```
 *
 * Throws [IllegalStateException] when the folder cannot be made, since a test cannot go on without it.
 */
public fun scratchDir(): Path {
    val root = tempRoot(::environmentVariable).canonicalPath()
    check(root is Result.Success) { "no temp folder for a scratch folder: $root" }
    val dir = Path(root.value, "kern-scratch-${Uuid.random()}")
    val created = dir.createDirectories().flatMap { dir.chmod(SCRATCH_MODE) }
    check(created is Result.Success) { "no scratch folder at $dir: $created" }
    return dir
}

private const val SCRATCH_MODE = 0b111_101_101

/** `TMPDIR` as [env] gives it when that is an absolute path, else the platform's own temp folder. */
internal fun tempRoot(env: (String) -> String?): Path =
    env("TMPDIR")?.let(::Path)?.takeIf { it.isAbsolute } ?: platformTempRoot()

/** The environment variable [name], or null when it is unset. */
internal expect fun environmentVariable(name: String): String?

/** The platform's own temp folder, as an absolute path. */
internal expect fun platformTempRoot(): Path

/**
 * Makes this path a symlink to [target], stored as given, exactly as `createSymlink` from kern:fs does: a relative
 * [target] is read from this path's folder, and one that does not exist is allowed. The missing folders on the way are
 * made first, as `createDirectories` makes them.
 *
 * @return what `createSymlink` answers: [FileError.AlreadyExists] when anything is at this path,
 *   [FileError.InvalidName], [FileError.NameTooLong] or [FileError.NotFound] for a path it refuses, or
 *   [FileError.WriteFailed] otherwise; [FileError.NotFound] naming this path's folder when a file or anything else
 *   that is no folder stands where a folder on the way belongs, on every platform.
 */
@OptIn(InternalKernApi::class)
public fun Path.symlinkTo(target: Path): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    return createSymlink(target)
}

/**
 * Makes the missing folders this path lies in, as `createDirectories` makes them, and null once they are there;
 * [FileError.NotFound] naming the folder this path lies in when something that is no folder stands on the way, so a
 * helper answers the same on every platform; or what making them answered otherwise.
 */
internal fun Path.parentsMade(): FileError? {
    val folder = parent ?: return null
    val made = folder.createDirectories()
    if (made !is Result.Error) return null
    return when (made.error) {
        is FileError.NotADirectory, is FileError.NotFound -> FileError.NotFound(folder)
        else -> made.error
    }
}

/**
 * Makes this path a second name for the file at [target], a hard link, and the missing folders on the way first, as
 * [symlinkTo] makes them. This is unverified on Windows native.
 *
 * @return [FileError.AlreadyExists] when anything is at this path; [FileError.InvalidName], [FileError.NameTooLong]
 *   or [FileError.NotFound] for this path or [target] when an fs call would refuse it; [FileError.NotFound] naming
 *   this path's folder when a file or anything else that is no folder stands where a folder on the way belongs;
 *   [FileError.WriteFailed] when the link could not be made otherwise, with the cause an fs call gives, such as
 *   [com.fromwau.kern.fs.IoCause.NotFound] when [target] is missing.
 */
public expect fun Path.hardLinkTo(target: Path): EmptyResult<FileError>

/**
 * Sets the permission bits of what this path reaches, following symlinks, to [bits], such as `0b110_000_000` for
 * `600`, setuid, setgid and sticky included. The JVM on a file system without unix attributes sets the nine
 * permission bits alone, and Windows, native or JVM, keeps only whether the owner may write, as the file's read-only
 * flag.
 *
 * @return [FileError.NotFound] when nothing is at this path; [FileError.InvalidName], [FileError.NameTooLong] or
 *   [FileError.NotFound] for a path an fs call would refuse; or [FileError.WriteFailed], with the cause an fs call
 *   gives, when the bits could not be set.
 */
public expect fun Path.chmod(bits: Int): EmptyResult<FileError>

/**
 * Makes a FIFO, a named pipe, at this path, with the permissions the umask leaves of `666`, and the missing folders on
 * the way first, as `createDirectories` makes them. The JVM runs the `mkfifo` command, so it needs one on the `PATH`.
 * Windows has no FIFOs, so on Windows native this is [FileError.WriteFailed] for every path fs would accept.
 *
 * @return [FileError.AlreadyExists] when anything is at this path; [FileError.InvalidName], [FileError.NameTooLong]
 *   or [FileError.NotFound] for a path an fs call would refuse; [FileError.NotFound] naming this path's folder when a
 *   file or anything else that is no folder stands where a folder on the way belongs, on every platform; or
 *   [FileError.WriteFailed] when the FIFO could not be made otherwise.
 */
public expect fun Path.mkfifo(): EmptyResult<FileError>

/**
 * Sets when what this path reaches, following symlinks, was last written to [at], as `info().modifiedAt` then reads
 * it, such as to make a copy look older than its source. Linux and Apple keep it to the microsecond and set the access
 * time to it too; the JVM and Windows keep what the file system keeps. The JVM and Android need read access to the
 * file, so there a file of yours with mode `000` is [FileError.WriteFailed].
 *
 * @return [FileError.NotFound] when nothing is at this path, and for an empty path; [FileError.InvalidName] or
 *   [FileError.NameTooLong] for a path an fs call would refuse; or [FileError.WriteFailed], with the cause an fs call
 *   gives, when the time could not be set.
 */
@OptIn(InternalKernApi::class)
public fun Path.setModifiedAt(at: Instant): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    return try {
        setLastModified(this, at)
        Ok(Unit)
    } catch (e: Exception) {
        val cause = IoCause.of(e)
        Err(if (cause == IoCause.NotFound) FileError.NotFound(this) else FileError.WriteFailed(this, cause, e))
    }
}
