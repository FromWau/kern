package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * What is at this path, following symlinks, read in one lookup: its type, size, times, permissions and whether you own
 * it. [fileType], [fileSize], [modifiedAt] and [permissions] each answer one of these. Android reads the modification
 * time in a second lookup, so a file replaced in between can give the new file's time with the old one's other fields.
 *
 * ```kotlin
 * val info = secrets.info().getOrElse { return warn(it) }
 * if (info.ownedByMe == false) println("$secrets belongs to someone else")
 * ```
 *
 * @return the info; [FileError.NotFound] when nothing is there, or [FileError.Inaccessible] when it may not be looked
 *   up.
 */
public fun Path.info(): Result<FileInfo, FileError> {
    refusedPath()?.let { return it }
    return try {
        Ok(fileInfoAt(this))
    } catch (e: Exception) {
        inaccessibleUnlessGone(e)
    }
}

/**
 * What [info] read at a path. Fields a platform cannot read are null: on Windows, native or JVM, [changedAt],
 * [permissions] and [ownedByMe] all are, and on the JVM there [links] too. On the JVM, a file system with posix
 * permissions but no unix attributes has no [changedAt] or [links], and its [permissions] lack the setuid, setgid and
 * sticky bits. A Windows native device or pipe whose details Windows does not give has [size] 0, [modifiedAt]
 * 1601-01-01, Windows' zero time, and [links] null.
 *
 * @property type what is there, as [fileType] says.
 * @property size how many bytes a regular file holds; for anything else, what the platform reports.
 * @property modifiedAt when the content was last written, as [modifiedAt] says.
 * @property changedAt when the file's content or attributes last changed, such as its permissions or owner: the
 *   `ctime` of Linux and Apple. No call sets it, so a copy given an old [modifiedAt] still shows when it was made.
 * @property permissions the permission bits, as [permissions] gives them.
 * @property ownedByMe whether the user this process runs as owns the file.
 * @property links how many hard links the file has, its names in all folders: `1` for a file with no other name. Null
 *   where the platform does not say, as on the JVM on Windows or without unix attributes.
 */
public data class FileInfo(
    val type: FileType,
    val size: Long,
    val modifiedAt: Instant,
    val changedAt: Instant?,
    val permissions: Permissions?,
    val ownedByMe: Boolean?,
    val links: Int?,
)

/** [info] of [path] from the platform's own lookup, following symlinks, throwing why it could not be read. */
internal expect fun fileInfoAt(path: Path): FileInfo

/** [fileInfoAt] of what is at [path] itself, a symlink there described as the link, throwing why it cannot be read. */
internal expect fun ownFileInfoAt(path: Path): FileInfo

/**
 * What is at [path] itself, a symlink being [FileType.Symlink], from one lookup that does not follow a link at the last
 * name; null when nothing is there, throwing why it could not be looked up.
 */
internal expect fun ownFileTypeAt(path: Path): FileType?

/**
 * Sets the modification time of [path], following symlinks, to [time], throwing why it could not. Linux and Apple keep
 * it to the microsecond and set the access time to it too; elsewhere it is kept as finely as the platform keeps it.
 */
@InternalKernApi
public expect fun setLastModified(
    path: Path,
    time: Instant,
)

/**
 * [FileError.Refused] for [path], which this info describes, when someone besides this user may change it: another
 * user owns it, or its group or everyone else may write it, [asked] being the path the call was given. Null when only
 * this user may, and on a platform that says neither, such as Windows.
 */
internal fun FileInfo.untrustedAt(
    path: Path,
    asked: Path,
): FileError.Refused? {
    val why = when {
        ownedByMe == false -> RefusedBecause.OtherOwner
        permissions?.othersMayWrite == true -> RefusedBecause.OthersMayWrite
        else -> return null
    }
    return FileError.Refused(path, this, why, asked)
}

/**
 * [untrustedAt], or [FileError.Refused] with [RefusedBecause.OthersMayRead] when its group or everyone else may read
 * it: what a private [Writer] refuses of its root and of a file it reads.
 */
internal fun FileInfo.unprivateAt(
    path: Path,
    asked: Path,
): FileError.Refused? {
    untrustedAt(path, asked)?.let { return it }
    if (permissions?.othersMayRead != true) return null
    return FileError.Refused(path, this, RefusedBecause.OthersMayRead, asked)
}
