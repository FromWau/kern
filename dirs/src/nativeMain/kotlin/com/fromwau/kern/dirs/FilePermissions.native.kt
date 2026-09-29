@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.errno
import platform.posix.stat
import platform.posix.strerror

internal actual fun copyPermissions(
    from: Path,
    to: Path,
): Unit = memScoped {
    val info = alloc<stat>()
    if (stat(from.toString(), info.ptr) != 0) throw IOException("$from: ${errnoReason()}")
    // The permission bits alone: the rest of a mode says what kind of file it is, which the copy already is.
    if (setPermissionBits(to, info.st_mode.toInt() and PERMISSION_BITS) != 0) {
        throw IOException("$to: ${errnoReason()}")
    }
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

/** What the last system call went wrong with, in the words the platform has for it. */
internal fun errnoReason(): String = strerror(errno)?.toKString() ?: "errno $errno"
