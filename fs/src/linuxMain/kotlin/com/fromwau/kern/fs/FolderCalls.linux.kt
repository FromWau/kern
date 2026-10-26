@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import platform.linux.SYS_fchownat
import platform.linux.SYS_mkdirat
import platform.linux.SYS_openat
import platform.linux.SYS_readlinkat
import platform.linux.SYS_renameat2
import platform.linux.SYS_symlinkat
import platform.linux.SYS_unlinkat
import platform.posix.O_CLOEXEC
import platform.posix.O_NOFOLLOW
import platform.posix.close
import platform.posix.fstat
import platform.posix.stat
import platform.posix.syscall

internal actual val WORKING_DIRECTORY: Int = -100

internal actual fun openAt(
    folder: Int,
    name: String,
    flags: Int,
    mode: Int,
): Int = memScoped {
    syscall(SYS_openat.toLong(), folder.toLong(), name.cstr.ptr, flags.toLong(), mode.toLong()).toInt()
}

// fstat of an O_PATH open, which stays on a link: calling newfstatat directly would tie kern to one struct stat layout.
internal actual fun statusAt(
    folder: Int,
    name: String,
    into: CPointer<stat>,
): Int {
    val opened = openAt(folder, name, O_PATH or O_NOFOLLOW or O_CLOEXEC, 0)
    if (opened < 0) return -1
    val looked = fstat(opened, into)
    close(opened)
    return looked
}

internal actual fun makeFolderAt(
    folder: Int,
    name: String,
    mode: Int,
): Int = memScoped { syscall(SYS_mkdirat.toLong(), folder.toLong(), name.cstr.ptr, mode.toLong()).toInt() }

internal actual fun removeAt(
    folder: Int,
    name: String,
    isFolder: Boolean,
): Int = memScoped {
    val flags = if (isFolder) AT_REMOVEDIR else 0L
    syscall(SYS_unlinkat.toLong(), folder.toLong(), name.cstr.ptr, flags).toInt()
}

// renameat2 with no flags, since some Linux architectures, such as RISC-V, have no renameat call.
internal actual fun renameAt(
    fromFolder: Int,
    from: String,
    toFolder: Int,
    to: String,
): Int = memScoped {
    syscall(SYS_renameat2.toLong(), fromFolder.toLong(), from.cstr.ptr, toFolder.toLong(), to.cstr.ptr, 0L).toInt()
}

internal actual fun symlinkAt(
    target: String,
    folder: Int,
    name: String,
): Int = memScoped { syscall(SYS_symlinkat.toLong(), target.cstr.ptr, folder.toLong(), name.cstr.ptr).toInt() }

internal actual fun readLinkAt(
    folder: Int,
    name: String,
    buffer: CPointer<ByteVar>,
    size: Int,
): Int = memScoped {
    syscall(SYS_readlinkat.toLong(), folder.toLong(), name.cstr.ptr, buffer, size.toLong()).toInt()
}

internal actual fun changeGroupAt(
    folder: Int,
    name: String,
    group: UInt,
): Int = memScoped {
    syscall(SYS_fchownat.toLong(), folder.toLong(), name.cstr.ptr, UNCHANGED_ID, group.toLong(), AT_SYMLINK_NOFOLLOW)
        .toInt()
}

// Linux's values, which the headers above leave out with the calls that take them.
private const val O_PATH = 0x200000
private const val AT_REMOVEDIR = 0x200L
private const val AT_SYMLINK_NOFOLLOW = 0x100L

/** The id `fchownat` takes as "leave this one as it is", -1 as an unsigned 32-bit id. */
private const val UNCHANGED_ID = 0xFFFFFFFFL
