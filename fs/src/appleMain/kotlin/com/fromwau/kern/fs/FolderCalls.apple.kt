@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.convert
import platform.posix.AT_FDCWD
import platform.posix.AT_REMOVEDIR
import platform.posix.AT_SYMLINK_NOFOLLOW
import platform.posix.fchownat
import platform.posix.fstatat
import platform.posix.mkdirat
import platform.posix.readlinkat
import platform.posix.renameat
import platform.posix.stat
import platform.posix.symlinkat
import platform.posix.unlinkat

internal actual val WORKING_DIRECTORY: Int = AT_FDCWD

internal actual fun statusAt(
    folder: Int,
    name: String,
    into: CPointer<stat>,
): Int = fstatat(folder, name, into, AT_SYMLINK_NOFOLLOW)

internal actual fun makeFolderAt(
    folder: Int,
    name: String,
    mode: Int,
): Int = mkdirat(folder, name, mode.convert())

internal actual fun removeAt(
    folder: Int,
    name: String,
    isFolder: Boolean,
): Int = unlinkat(folder, name, if (isFolder) AT_REMOVEDIR else 0)

internal actual fun renameAt(
    fromFolder: Int,
    from: String,
    toFolder: Int,
    to: String,
): Int = renameat(fromFolder, from, toFolder, to)

internal actual fun symlinkAt(
    target: String,
    folder: Int,
    name: String,
): Int = symlinkat(target, folder, name)

internal actual fun readLinkAt(
    folder: Int,
    name: String,
    buffer: CPointer<ByteVar>,
    size: Int,
): Int = readlinkat(folder, name, buffer, size.convert()).convert()

internal actual fun changeGroupAt(
    folder: Int,
    name: String,
    group: UInt,
): Int = fchownat(folder, name, UInt.MAX_VALUE, group, AT_SYMLINK_NOFOLLOW)
