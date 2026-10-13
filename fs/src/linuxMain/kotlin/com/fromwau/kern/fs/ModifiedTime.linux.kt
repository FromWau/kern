package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.stat
import kotlin.time.Instant

// Linux names the field st_mtim and Apple st_mtimespec, so the two cannot share posixMain.
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun lastModified(path: Path): Instant? = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) return@memScoped null
    Instant.fromEpochSeconds(info.st_mtim.tv_sec, info.st_mtim.tv_nsec)
}
