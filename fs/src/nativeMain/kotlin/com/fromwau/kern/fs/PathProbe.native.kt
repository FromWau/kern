package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.stat

// One stat call, whose errno tells a denial from a missing path where kotlinx-io's null does not.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun probePath(path: Path): PathProbe = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) return@memScoped deniedUnlessGone(path) ?: PathProbe.Absent

    val mode = info.st_mode.toInt() and S_IFMT
    // st_size is a Long on Linux and Apple but not on mingw, where the conversion is the one that compiles.
    @Suppress("REDUNDANT_CALL_OF_CONVERSION_METHOD")
    PathProbe.Present(
        FileMetadata(
            isRegularFile = mode == S_IFREG,
            isDirectory = mode == S_IFDIR,
            size = if (mode == S_IFREG) info.st_size.toLong() else -1L,
        ),
    )
}

/** The errno a lookup of [path] just failed with as a [PathProbe.Denied], or null when it says nothing is there. */
internal fun deniedUnlessGone(path: Path): PathProbe.Denied? = when (errno) {
    // ENOTDIR is a path running through a file: it resolves to nothing, and never can while that file is there,
    // which is absence rather than a refusal.
    ENOENT, ENOTDIR -> null
    else -> PathProbe.Denied(errnoFailure(about = path))
}
