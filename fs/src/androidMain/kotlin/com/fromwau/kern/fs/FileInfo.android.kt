package com.fromwau.kern.fs

import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import kotlin.time.Instant

internal actual fun fileInfoAt(path: Path): FileInfo = fileInfoOf(path, followLinks = true)

internal actual fun ownFileInfoAt(path: Path): FileInfo = fileInfoOf(path, followLinks = false)

/**
 * [fileInfoAt] of [path], a link at it followed when [followLinks], throwing why it could not be read. Two lookups:
 * java.nio gives the modification time to the nanosecond on every API level, while the owner, the mode and the ctime
 * come from the system call, so a file replaced between the two can mix both.
 */
private fun fileInfoOf(
    path: Path,
    followLinks: Boolean,
): FileInfo {
    val options = if (followLinks) emptyArray<LinkOption>() else arrayOf(LinkOption.NOFOLLOW_LINKS)
    val modified = Files
        .readAttributes(Paths.get(path.toString()), BasicFileAttributes::class.java, *options)
        .lastModifiedTime()
        .toInstant()
    val status = osCall(path) { if (followLinks) Os.stat(path.toString()) else Os.lstat(path.toString()) }
    return FileInfo(
        type = fileTypeOfMode(status.st_mode),
        size = status.st_size,
        modifiedAt = Instant.fromEpochSeconds(modified.epochSecond, modified.nano),
        changedAt = status.changedAt(),
        permissions = Permissions(status.st_mode and PERMISSION_BITS),
        ownedByMe = status.st_uid == Os.geteuid(),
        links = status.st_nlink.toInt(),
    )
}

internal actual fun ownFileTypeAt(path: Path): FileType? = try {
    fileTypeOfMode(Os.lstat(path.toString()).st_mode)
} catch (e: ErrnoException) {
    if (e.errno == OsConstants.ENOENT || e.errno == OsConstants.ENOTDIR) null else throw e.typed(path)
}

// The nanoseconds are there from API 27; below it the ctime counts whole seconds.
private fun StructStat.changedAt(): Instant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
    Instant.fromEpochSeconds(st_ctim.tv_sec, st_ctim.tv_nsec)
} else {
    Instant.fromEpochSeconds(st_ctime)
}
