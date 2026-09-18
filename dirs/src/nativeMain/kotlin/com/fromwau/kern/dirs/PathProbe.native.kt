package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.errno
import platform.posix.stat
import platform.posix.strerror

// One implementation for every native target, though only Apple needs it: Linux and mingw already throw for a
// denial instead of returning null, so there this only confirms the ENOENT kotlinx-io implied.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun probePath(path: Path): PathProbe = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) {
        return@memScoped when (errno) {
            // ENOTDIR is a path running through a file: it resolves to nothing, and never can while that
            // file is there, which is absence rather than a refusal.
            ENOENT, ENOTDIR -> PathProbe.Absent
            else -> PathProbe.Denied(strerror(errno)?.toKString() ?: "errno $errno")
        }
    }

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

// kotlinx-io throws here when a directory may not be read, so an empty listing is always a real one.
internal actual fun listDenial(path: Path): PathProbe.Denied? = null
