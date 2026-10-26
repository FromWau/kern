package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.UserPrincipal
import kotlin.time.Instant
import java.nio.file.Path as NioPath

internal actual fun fileInfoAt(path: Path): FileInfo = fileInfoWith(path, AttributeViews.here)

internal actual fun ownFileInfoAt(path: Path): FileInfo =
    fileInfoWith(path, AttributeViews.here, LinkOption.NOFOLLOW_LINKS)

internal actual fun ownFileTypeAt(path: Path): FileType? = try {
    val file = Paths.get(path.toString())
    if (AttributeViews.here == AttributeViews.Unix) {
        fileTypeOfMode(Files.getAttribute(file, "unix:mode", LinkOption.NOFOLLOW_LINKS) as Int)
    } else {
        val basic = Files.readAttributes(file, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        if (basic.isSymbolicLink) FileType.Symlink else basic.type()
    }
} catch (e: Exception) {
    if (e.asProbe() == PathProbe.Absent) null else throw e
}

/** The richest attribute view java.nio reads on a file system, which decides what [fileInfoWith] can fill in. */
internal enum class AttributeViews {
    /** Mode, uid and ctime, as on Linux and macOS. */
    Unix,

    /** The nine permission bits and the owner, with no ctime or special bits. */
    Posix,

    /** Type, size and times alone, as on Windows. */
    Basic,

    ;

    companion object {
        val here: AttributeViews = richest(unix = hasUnixView, posix = hasPosixView)

        /** The richest of the views a file system offers, by whether it has the [unix] and the [posix] one. */
        fun richest(
            unix: Boolean,
            posix: Boolean,
        ): AttributeViews = when {
            unix -> Unix
            posix -> Posix
            else -> Basic
        }
    }
}

/** [fileInfoAt] of [path] read through [views], with [options] for a link at it, throwing why it could not be read. */
internal fun fileInfoWith(
    path: Path,
    views: AttributeViews,
    vararg options: LinkOption,
): FileInfo {
    val file = Paths.get(path.toString())
    return when (views) {
        AttributeViews.Unix -> unixInfo(file, options)
        AttributeViews.Posix -> {
            val posix = Files.readAttributes(file, PosixFileAttributes::class.java, *options)
            FileInfo(
                type = posix.type(),
                size = posix.size(),
                modifiedAt = posix.lastModifiedTime().toKotlinInstant(),
                changedAt = null,
                permissions = Permissions(posix.permissions().sumOf { it.bit }),
                ownedByMe = ownedByCurrentUser(file, posix.owner()),
                links = null,
            )
        }
        AttributeViews.Basic -> {
            val basic = Files.readAttributes(file, BasicFileAttributes::class.java, *options)
            FileInfo(
                type = basic.type(),
                size = basic.size(),
                modifiedAt = basic.lastModifiedTime().toKotlinInstant(),
                changedAt = null,
                permissions = null,
                ownedByMe = null,
                links = null,
            )
        }
    }
}

private fun unixInfo(
    file: NioPath,
    options: Array<out LinkOption>,
): FileInfo {
    val unix = Files.readAttributes(file, "unix:*", *options)
    val mode = unix.getValue("mode") as Int
    return FileInfo(
        type = fileTypeOfMode(mode),
        size = unix.getValue("size") as Long,
        modifiedAt = (unix.getValue("lastModifiedTime") as FileTime).toKotlinInstant(),
        changedAt = (unix.getValue("ctime") as FileTime).toKotlinInstant(),
        permissions = Permissions(mode and PERMISSION_BITS),
        ownedByMe = currentUid?.let { unix["uid"] == it } ?: ownedByCurrentUser(file, Files.getOwner(file, *options)),
        links = unix.getValue("nlink") as Int,
    )
}

private fun BasicFileAttributes.type(): FileType = when {
    isRegularFile -> FileType.Regular
    isDirectory -> FileType.Directory
    isSymbolicLink -> FileType.Symlink
    else -> FileType.Other
}

/** Whether [owner] is the user this process runs as, by name, or null when that user cannot be looked up. */
private fun ownedByCurrentUser(
    file: NioPath,
    owner: UserPrincipal,
): Boolean? = try {
    owner == file.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
} catch (_: Exception) {
    null
}

private fun FileTime.toKotlinInstant(): Instant {
    val time = toInstant()
    return Instant.fromEpochSeconds(time.epochSecond, time.nano)
}
