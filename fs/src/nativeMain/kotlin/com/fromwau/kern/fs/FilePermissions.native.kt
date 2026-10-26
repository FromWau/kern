@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.stat

internal actual fun permissionBits(path: Path): Int? = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) throw errnoFailure(about = path)
    // The permission bits alone: the rest of a mode says what kind of file it is, which a copy already is.
    info.st_mode.toInt() and PERMISSION_BITS
}

internal actual fun setFilePermissions(
    path: Path,
    bits: Int,
) {
    if (setPermissionBits(path, bits) != 0) throw errnoFailure(about = path)
}

/**
 * `chmod` on [path], answering 0 or -1 as it does.
 *
 * Declared per target although every target has the call: a mode is a different width on each, so the
 * commonized posix package this shared code compiles against does not carry `chmod` at all. On Windows a
 * mode says only whether the file is read-only, which is then what gets copied.
 */
internal expect fun setPermissionBits(
    path: Path,
    bits: Int,
): Int
