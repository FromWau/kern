package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.O_CLOEXEC
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

internal actual fun isSameFile(
    first: Path,
    second: Path,
): Boolean {
    val key = fileKeyOrNull(first) ?: return false
    return key == fileKeyOrNull(second)
}

internal actual fun syncDirectory(directory: Path): Exception? = sync(directory)

private fun sync(path: Path): Exception? {
    val descriptor = open(path.toString(), O_RDONLY or O_CLOEXEC)
    if (descriptor < 0) return errnoFailure(about = path)
    val failure = if (fsync(descriptor) == 0) null else errnoFailure(about = path)
    close(descriptor)
    return failure
}

internal actual val renameReplacesAtomically: Boolean = true
