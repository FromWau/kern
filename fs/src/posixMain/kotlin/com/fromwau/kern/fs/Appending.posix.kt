@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_APPEND
import platform.posix.O_CREAT
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.pread
import platform.posix.stat

internal actual fun openAppendFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): Result<AppendFile, FileError> = openAppendFileAt(Opening(path), permissions, newMode)

/** [openAppendFile] of what [opening] names. */
internal fun openAppendFileAt(
    opening: Opening,
    permissions: Int?,
    newMode: Int,
): Result<AppendFile, FileError> {
    val path = opening.path
    val opened = try {
        openForAppending(opening, permissions, newMode)
    } catch (e: Exception) {
        return Err(e.writeError(path))
    }
    val descriptor = when (val moved = aboveStandardStreams(path, opened)) {
        is Result.Success -> moved.value
        is Result.Error -> return moved
    }

    val key = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) == 0) Ok(info.key()) else Err(writeFailed(path, errnoFailure(about = path)))
    }
    if (key is Result.Error) close(descriptor)
    return key.map { DescriptorAppendFile(path, descriptor, it) }
}

/**
 * Opens [opening] to append to, creating it with [permissions] when given, which a file already there gets too, and
 * with [newMode] otherwise.
 */
private fun openForAppending(
    opening: Opening,
    permissions: Int?,
    newMode: Int,
): Int {
    val creationMode = permissions ?: newMode
    val descriptor = try {
        // Read access lets lastByte read the open file; a file this process may only write is still appended to.
        openRegularFile(opening, O_RDWR or O_APPEND or O_CREAT, creationMode)
    } catch (e: OsCallFailure) {
        if (e.ioCause != IoCause.Denied) throw e
        openRegularFile(opening, O_WRONLY or O_APPEND or O_CREAT, creationMode)
    }
    // Set as well as created with, since the umask can narrow a new file's mode and one already there has its own.
    if (permissions != null && setDescriptorPermissionBits(descriptor, permissions) != 0) {
        val failure = errnoFailure(about = opening.path)
        close(descriptor)
        throw failure
    }
    return descriptor
}

private class DescriptorAppendFile(
    override val path: Path,
    private val descriptor: Int,
    private val opened: FileKey,
) : AppendFile {
    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.Closed(path))
        return writeFully(descriptor, bytes, path)?.let { Err(writeFailed(path, it)) } ?: Ok(Unit)
    }

    override fun state(): Result<AppendState, FileError> = memScoped {
        if (closed) return@memScoped Ok(AppendState.Closed)
        val info = alloc<stat>()
        // The path before the open file, so a delete that took the name away also shows in the link count.
        val atPath = if (stat(path.toString(), info.ptr) == 0) {
            info.key()
        } else {
            deniedUnlessGone(path)?.let { return@memScoped Err(inaccessible(path, it.failure)) }
        }
        if (fstat(descriptor, info.ptr) != 0) return@memScoped Err(inaccessible(path, errnoFailure(about = path)))
        Ok(
            when {
                info.st_nlink.convert<Long>() == 0L -> AppendState.Deleted
                atPath == opened -> AppendState.Same
                else -> AppendState.Moved
            },
        )
    }

    override fun lastByte(): Result<Byte?, FileError> {
        if (closed) return Err(FileError.Closed(path))
        return memScoped {
            val info = alloc<stat>()
            if (fstat(descriptor, info.ptr) != 0) return@memScoped Err(inaccessible(path, errnoFailure(about = path)))
            val size = info.st_size.convert<Long>()
            if (size <= 0L) return@memScoped Ok(null)
            val byte = alloc<ByteVar>()
            when (pread(descriptor, byte.ptr, 1.convert(), (size - 1).convert()).convert<Long>()) {
                1L -> Ok(byte.value)
                // Shrunk since the size was read: no call failed, so errno holds nothing about this.
                0L -> Err(inaccessible(path, failure = null))
                else -> Err(inaccessible(path, errnoFailure(about = path)))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        close(descriptor)
    }
}

/** The device and inode, which tell one file from another on a machine. */
internal data class FileKey(val device: Long, val inode: Long)

internal fun stat.key(): FileKey = FileKey(deviceId(), st_ino.convert<Long>())

/** `st_dev`, whose `dev_t` is unsigned on Linux and signed on Apple, so each target converts its own. */
internal expect fun stat.deviceId(): Long

/**
 * Moves [descriptor] above 0, 1 and 2, or answers why it could not. With standard output or error closed, `open`
 * hands out that number, and every console line would land in the file.
 */
private fun aboveStandardStreams(path: Path, descriptor: Int): Result<Int, FileError> {
    if (descriptor > STDERR_FILENO) return Ok(descriptor)
    val moved = fcntl(descriptor, F_DUPFD_CLOEXEC, STDERR_FILENO + 1)
    val failure = if (moved < 0) writeFailed(path, errnoFailure(about = path)) else null
    close(descriptor)
    return if (failure == null) Ok(moved) else Err(failure)
}
