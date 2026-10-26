package com.fromwau.kern.fs

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path

// Android asks the system calls itself rather than sharing the JVM's implementation: `Os.chmod` is there from
// API 21 and does one thing, while whether Android's own `java.nio.file` hands out a posix attribute view is
// not something this project can run a test for. A view it declined would leave this doing nothing, quietly.
internal actual fun permissionBits(path: Path): Int? =
    osCall(path) { Os.stat(path.toString()).st_mode } and PERMISSION_BITS

internal actual fun setFilePermissions(
    path: Path,
    bits: Int,
) {
    osCall(path) { Os.chmod(path.toString(), bits) }
}

internal actual fun copyGroup(
    from: Path,
    to: Path,
): Boolean = try {
    val group = Os.stat(from.toString()).st_gid
    if (Os.stat(to.toString()).st_gid != group) Os.chown(to.toString(), -1, group)
    true
} catch (_: ErrnoException) {
    false
}

/** Runs the system call [block] on [path], throwing its failure typed by the `errno` it set. */
internal inline fun <T> osCall(
    path: Path,
    block: () -> T,
): T = try {
    block()
} catch (e: ErrnoException) {
    throw e.typed(path)
}

internal fun ErrnoException.typed(path: Path): OsCallFailure =
    OsCallFailure(causeOfErrno(errno, ::androidErrnoValue), path, message ?: "errno $errno")

private fun androidErrnoValue(name: Errno): Int = when (name) {
    Errno.EACCES -> OsConstants.EACCES
    Errno.EPERM -> OsConstants.EPERM
    Errno.ENOENT -> OsConstants.ENOENT
    Errno.EROFS -> OsConstants.EROFS
    Errno.ENOSPC -> OsConstants.ENOSPC
    Errno.EDQUOT -> OsConstants.EDQUOT
    Errno.ELOOP -> OsConstants.ELOOP
}
