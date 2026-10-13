package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.geteuid
import platform.posix.open
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun fileIdentity(path: Path): FileIdentity? = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) return@memScoped null
    FileIdentity(
        linkCount = info.st_nlink.convert(),
        ownedByCurrentUser = info.st_uid == geteuid(),
    )
}

// Linux and Apple both fsync a read-only descriptor, which also reaches a file this process may no longer write.
internal actual fun syncFile(path: Path): String? = sync(path)

internal actual fun syncDirectory(directory: Path): String? = sync(directory)

private fun sync(path: Path): String? {
    val descriptor = open(path.toString(), O_RDONLY)
    if (descriptor < 0) return errnoReason()
    val reason = if (fsync(descriptor) == 0) null else errnoReason()
    close(descriptor)
    return reason
}

internal actual val renameReplacesAtomically: Boolean = true
