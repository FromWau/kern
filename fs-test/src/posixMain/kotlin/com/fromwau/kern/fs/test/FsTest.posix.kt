@file:OptIn(ExperimentalForeignApi::class, InternalKernApi::class)

package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.alreadyExists
import com.fromwau.kern.fs.ofErrno
import com.fromwau.kern.fs.pathError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.EEXIST
import platform.posix.ENOENT
import platform.posix.errno
import platform.posix.link
import platform.posix.strerror

public actual fun Path.hardLinkTo(target: Path): EmptyResult<FileError> {
    (pathError() ?: target.pathError())?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    return made(link(target.toString(), toString()))
}

public actual fun Path.chmod(bits: Int): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    if (setPermissionBits(this, bits) == 0) return Ok(Unit)
    val code = errno
    return Err(if (code == ENOENT) FileError.NotFound(this) else writeFailed(code))
}

public actual fun Path.mkfifo(): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    return made(makeFifo(this, NEW_FIFO_MODE))
}

// mode_t is 32 bits on Linux and 16 on Apple, so each passes the mode at its own width.

/** `chmod` of [path] to [bits], answering 0 or -1 with `errno` set. */
internal expect fun setPermissionBits(
    path: Path,
    bits: Int,
): Int

/** `mkfifo` at [path] with [mode], answering 0 or -1 with `errno` set. */
internal expect fun makeFifo(
    path: Path,
    mode: Int,
): Int

/** What a call that made this path and answered [result], 0 or -1 with `errno` set, comes to. */
private fun Path.made(result: Int): EmptyResult<FileError> {
    if (result == 0) return Ok(Unit)
    val code = errno
    return Err(if (code == EEXIST) alreadyExists() else writeFailed(code))
}

private fun Path.writeFailed(code: Int): FileError.WriteFailed {
    val words = strerror(code)?.toKString() ?: "errno $code"
    return FileError.WriteFailed(this, IoCause.ofErrno(code), IOException("$this: $words"))
}

/** `0666`, which the umask narrows as it does for any new file. */
private const val NEW_FIFO_MODE = 0b110_110_110
