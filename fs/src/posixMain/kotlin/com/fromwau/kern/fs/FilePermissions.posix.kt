package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.chown
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun copyGroup(
    from: Path,
    to: Path,
): Boolean = memScoped {
    val source = alloc<stat>()
    val copy = alloc<stat>()
    if (stat(from.toString(), source.ptr) != 0 || stat(to.toString(), copy.ptr) != 0) return@memScoped false
    // Asked only when the groups differ: a file system without groups, such as FAT, refuses any chown.
    source.st_gid == copy.st_gid || chown(to.toString(), UInt.MAX_VALUE, source.st_gid) == 0
}
