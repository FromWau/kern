package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.stat
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun isSymlink(path: Path): Boolean = memScoped {
    val info = alloc<stat>()
    lstat(path.toString(), info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFLNK
}

internal actual fun deleteLink(path: Path): String? = if (unlink(path.toString()) == 0) null else errnoReason()

internal actual fun realPath(path: Path): Path = resolvedOrSelf(path)
