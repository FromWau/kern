@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.posix.EACCES
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.ENOTDIR
import platform.posix.F_DUPFD_CLOEXEC
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_NONBLOCK
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.STDERR_FILENO
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.write

private const val APPEND_FILE_MODE = 420 // 0644

// O_NONBLOCK keeps a FIFO that appeared after the caller's check from blocking the open; a regular file ignores it.
private val APPEND_FLAGS = O_APPEND or O_CREAT or O_CLOEXEC or O_NONBLOCK

internal actual fun openAppendFile(path: Path): Result<AppendFile, FileError> {
    // Read access lets lastByte read the open file; a file this process may only write is still appended to.
    var opened = open(path.toString(), O_RDWR or APPEND_FLAGS, APPEND_FILE_MODE)
    if (opened < 0 && errno == EACCES) opened = open(path.toString(), O_WRONLY or APPEND_FLAGS, APPEND_FILE_MODE)
    if (opened < 0) return Err(FileError.WriteFailed(path, errnoReason()))
    val descriptor = when (val moved = aboveStandardStreams(path, opened)) {
        is Result.Success -> moved.value
        is Result.Error -> return moved
    }

    val status = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) null else (info.st_mode.convert<Int>() and S_IFMT) to info.key()
    }
    if (status == null) {
        val reason = errnoReason()
        close(descriptor)
        return Err(FileError.WriteFailed(path, reason))
    }
    val (type, key) = status
    if (type != S_IFREG) {
        close(descriptor)
        return Err(FileError.NotRegularFile(path, if (type == S_IFDIR) FileType.Directory else FileType.Other))
    }
    return Ok(DescriptorAppendFile(path, descriptor, key))
}

private class DescriptorAppendFile(
    override val path: Path,
    private val descriptor: Int,
    private val opened: FileKey,
) : AppendFile {
    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.WriteFailed(path, CLOSED))
        if (bytes.isEmpty()) return Ok(Unit)
        bytes.usePinned { pinned ->
            var written = 0
            // A regular file takes the whole write at once; the loop only covers an interrupted or short one.
            while (written < bytes.size) {
                val result = write(descriptor, pinned.addressOf(written), (bytes.size - written).convert())
                when {
                    result >= 0 -> written += result.toInt()
                    errno != EINTR -> return Err(FileError.WriteFailed(path, errnoReason()))
                }
            }
        }
        return Ok(Unit)
    }

    override fun state(): AppendState = memScoped {
        val info = alloc<stat>()
        if (closed || fstat(descriptor, info.ptr) != 0) return@memScoped AppendState.Unknown
        if (info.st_nlink.convert<Long>() == 0L) return@memScoped AppendState.Deleted
        when {
            stat(path.toString(), info.ptr) == 0 -> if (info.key() == opened) AppendState.Same else AppendState.Moved
            errno == ENOENT || errno == ENOTDIR -> AppendState.Moved
            else -> AppendState.Unknown
        }
    }

    override fun lastByte(): Result<Byte?, FileError> {
        if (closed) return Err(FileError.Inaccessible(path, CLOSED))
        return memScoped {
            val info = alloc<stat>()
            if (fstat(descriptor, info.ptr) != 0) return@memScoped Err(FileError.Inaccessible(path, errnoReason()))
            val size = info.st_size.convert<Long>()
            if (size <= 0L) return@memScoped Ok(null)
            val byte = alloc<ByteVar>()
            if (pread(descriptor, byte.ptr, 1.convert(), (size - 1).convert()).convert<Long>() != 1L) {
                Err(FileError.Inaccessible(path, errnoReason()))
            } else {
                Ok(byte.value)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        close(descriptor)
    }
}

private const val CLOSED = "the file was closed"

/** The device and inode, which tell one file from another on a machine. */
private data class FileKey(val device: Long, val inode: Long)

private fun stat.key(): FileKey = FileKey(deviceId(), st_ino.convert<Long>())

/** `st_dev`, whose `dev_t` is unsigned on Linux and signed on Apple, so each target converts its own. */
internal expect fun stat.deviceId(): Long

/**
 * Moves [descriptor] above 0, 1 and 2, or answers why it could not. With standard output or error closed, `open`
 * hands out that number, and every console line would land in the file.
 */
private fun aboveStandardStreams(path: Path, descriptor: Int): Result<Int, FileError> {
    if (descriptor > STDERR_FILENO) return Ok(descriptor)
    val moved = fcntl(descriptor, F_DUPFD_CLOEXEC, STDERR_FILENO + 1)
    val failure = if (moved < 0) FileError.WriteFailed(path, errnoReason()) else null
    close(descriptor)
    return if (failure == null) Ok(moved) else Err(failure)
}
