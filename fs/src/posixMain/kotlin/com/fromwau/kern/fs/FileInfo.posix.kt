@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.fstat
import platform.posix.geteuid
import platform.posix.lstat
import platform.posix.stat
import kotlin.time.Instant

internal actual fun fileInfoAt(path: Path): FileInfo = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) throw errnoFailure(about = path)
    info.toFileInfo()
}

internal actual fun ownFileInfoAt(path: Path): FileInfo = memScoped {
    val info = alloc<stat>()
    if (lstat(path.toString(), info.ptr) != 0) throw errnoFailure(about = path)
    info.toFileInfo()
}

internal actual fun ownFileTypeAt(path: Path): FileType? = memScoped {
    val info = alloc<stat>()
    if (lstat(path.toString(), info.ptr) != 0) {
        val denied = deniedUnlessGone(path) ?: return@memScoped null
        throw denied.failure
    }
    fileTypeOfMode(info.st_mode.toInt())
}

/** [info] of the file open as [descriptor], on [path], throwing why it could not be read. */
internal fun fileInfoOf(
    descriptor: Int,
    path: Path,
): FileInfo = memScoped {
    val info = alloc<stat>()
    if (fstat(descriptor, info.ptr) != 0) throw errnoFailure(about = path)
    info.toFileInfo()
}

internal fun stat.toFileInfo(): FileInfo {
    val mode = st_mode.toInt()
    return FileInfo(
        type = fileTypeOfMode(mode),
        size = st_size.convert(),
        modifiedAt = modifiedAt(),
        changedAt = changedAt(),
        permissions = Permissions(mode and PERMISSION_BITS),
        ownedByMe = st_uid == geteuid(),
        links = st_nlink.convert(),
    )
}

/** This status's `mtime`, when the content was last written. */
internal expect fun stat.modifiedAt(): Instant

/** This status's `ctime`, when the content or the attributes last changed. */
internal expect fun stat.changedAt(): Instant
