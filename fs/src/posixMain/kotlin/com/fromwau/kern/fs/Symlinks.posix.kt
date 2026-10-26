package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.PATH_MAX
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.rename
import platform.posix.stat
import platform.posix.symlink
import platform.posix.unlink

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun isSymlink(path: Path): Boolean = memScoped {
    val info = alloc<stat>()
    lstat(path.toString(), info.ptr) == 0 && (info.st_mode.toInt() and S_IFMT) == S_IFLNK
}

internal actual fun readLink(path: Path): Path = readLinkIn(WORKING_DIRECTORY, path.toString(), about = path)

/** What the symlink [name] in the folder open as [folder] holds, as written, [about] naming it in a failure. */
@OptIn(ExperimentalForeignApi::class)
internal fun readLinkIn(
    folder: Int,
    name: String,
    about: Path,
): Path = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    // The target comes without a terminating NUL, and one that fills the buffer may have been cut short.
    val length = readLinkAt(folder, name, buffer, PATH_MAX)
    if (length < 0) throw errnoFailure(about = about)
    if (length >= PATH_MAX) throw OsCallFailure(IoCause.Other, about, "link target longer than $PATH_MAX bytes")
    Path(buffer.readBytes(length).decodeToString())
}

internal actual fun deleteLink(path: Path): Exception? =
    if (unlink(path.toString()) == 0) null else errnoFailure(about = path)

internal actual fun moveLink(
    path: Path,
    target: Path,
): Exception? = if (rename(path.toString(), target.toString()) == 0) null else errnoFailure(about = path)

internal actual fun createLink(
    path: Path,
    target: Path,
): Exception? = if (symlink(target.toString(), path.toString()) == 0) null else errnoFailure(about = path)

// kotlinx-io resolves with realpath here, which follows links.
internal actual fun realPath(path: Path): Path = SystemFileSystem.resolve(path)
