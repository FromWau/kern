package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.UserPrincipal
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Instant
import java.nio.file.Path as NioPath

// java.nio answers `unix:uid` as the uid_t's bits in a signed Int.
public actual fun currentUserId(): Long? = currentUid?.toUInt()?.toLong()

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
    me: CurrentUser = CurrentUser.here,
): FileInfo {
    val file = Paths.get(path.toString())
    return when (views) {
        AttributeViews.Unix -> unixInfo(file, options, me)
        AttributeViews.Posix -> posixInfo(file, options, me)
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

// Named rather than `unix:*`, which also resolves the owner's and group's names: a user database lookup per file.
internal fun unixAttributes(
    file: NioPath,
    options: Array<out LinkOption>,
): Map<String, Any?> = Files.readAttributes(file, "unix:mode,size,lastModifiedTime,ctime,uid,nlink", *options)

private fun unixInfo(
    file: NioPath,
    options: Array<out LinkOption>,
    me: CurrentUser,
): FileInfo {
    // Only through unixAttributes: the name-lookup guard in FileInfoJvmTest checks its keys, not this call.
    val unix = unixAttributes(file, options)
    val mode = unix.getValue("mode") as Int
    return FileInfo(
        type = fileTypeOfMode(mode),
        size = unix.getValue("size") as Long,
        modifiedAt = (unix.getValue("lastModifiedTime") as FileTime).toKotlinInstant(),
        changedAt = (unix.getValue("ctime") as FileTime).toKotlinInstant(),
        permissions = Permissions(mode and PERMISSION_BITS),
        ownedByMe = me.ownsUnix(file, options, unix.getValue("uid") as Int),
        links = unix.getValue("nlink") as Int,
    )
}

private fun BasicFileAttributes.type(): FileType = fileType(isRegularFile, isDirectory, isSymbolicLink)

private fun fileType(
    regular: Boolean,
    directory: Boolean,
    symlink: Boolean,
): FileType = when {
    regular -> FileType.Regular
    directory -> FileType.Directory
    symlink -> FileType.Symlink
    else -> FileType.Other
}

// Named rather than `posix:*`, which also resolves the group's name: a user database lookup per file.
internal fun posixAttributes(
    file: NioPath,
    options: Array<out LinkOption>,
): Map<String, Any?> = Files.readAttributes(
    file,
    "posix:permissions,owner,size,lastModifiedTime,isRegularFile,isDirectory,isSymbolicLink",
    *options,
)

private fun posixInfo(
    file: NioPath,
    options: Array<out LinkOption>,
    me: CurrentUser,
): FileInfo {
    // Only through posixAttributes: the name-lookup guard in FileInfoJvmTest checks its keys, not this call.
    val posix = posixAttributes(file, options)
    @Suppress("UNCHECKED_CAST")
    val permissions = posix.getValue("permissions") as Set<PosixFilePermission>
    return FileInfo(
        type = fileType(
            regular = posix.getValue("isRegularFile") as Boolean,
            directory = posix.getValue("isDirectory") as Boolean,
            symlink = posix.getValue("isSymbolicLink") as Boolean,
        ),
        size = posix.getValue("size") as Long,
        modifiedAt = (posix.getValue("lastModifiedTime") as FileTime).toKotlinInstant(),
        changedAt = null,
        permissions = Permissions(permissions.sumOf { it.bit }),
        ownedByMe = me.owns(posix.getValue("owner") as UserPrincipal),
        links = null,
    )
}

/** The user this process runs as, to tell whether it owns a file, looking each user up by name once. */
internal class CurrentUser(
    private val uid: Int?,
    lookUp: () -> UserPrincipal?,
    private val ownerOf: (NioPath, Array<out LinkOption>) -> Map<String, Any?> = { file, options ->
        Files.readAttributes(file, "unix:uid,owner", *options)
    },
) {
    private val principal by lazy(lookUp)
    private val ownsByUid = ConcurrentHashMap<Int, Boolean>()

    /** Whether [owner] is this user, or null when this user cannot be looked up. */
    fun owns(owner: UserPrincipal): Boolean? = principal?.let { owner == it }

    /** Whether [file], whose owner's uid is [fileUid], is this user's, or null when this user cannot be looked up. */
    fun ownsUnix(
        file: NioPath,
        options: Array<out LinkOption>,
        fileUid: Int,
    ): Boolean? {
        if (uid != null) return fileUid == uid
        val me = principal ?: return null
        ownsByUid[fileUid]?.let { return it }
        // Keyed by this read's uid, not fileUid, so a file chowned in between cannot store a wrong answer.
        val read = ownerOf(file, options)
        val owns = read.getValue("owner") as UserPrincipal == me
        ownsByUid[read.getValue("uid") as Int] = owns
        return owns
    }

    companion object {
        val here = CurrentUser(currentUid, lookUp = ::lookUpCurrentUser)
    }
}

/** This user's principal, or null when `user.name` is unset or the lookup fails in a way the JDK documents. */
private fun lookUpCurrentUser(): UserPrincipal? = try {
    System.getProperty("user.name")?.let { name ->
        FileSystems.getDefault().userPrincipalLookupService.lookupPrincipalByName(name)
    }
} catch (_: IOException) {
    null
} catch (_: UnsupportedOperationException) {
    null
} catch (_: SecurityException) {
    null
}

private fun FileTime.toKotlinInstant(): Instant {
    val time = toInstant()
    return Instant.fromEpochSeconds(time.epochSecond, time.nano)
}
