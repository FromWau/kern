@file:OptIn(ExperimentalForeignApi::class, InternalKernApi::class)

package com.fromwau.kern.fs.test

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.alreadyExists
import com.fromwau.kern.fs.ofErrno
import com.fromwau.kern.fs.ofWindowsError
import com.fromwau.kern.fs.pathError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.posix.ENOENT
import platform.posix.S_IREAD
import platform.posix.S_IWRITE
import platform.posix.chmod
import platform.posix.errno
import platform.windows.CreateHardLinkW
import platform.windows.ERROR_ALREADY_EXISTS
import platform.windows.GetLastError

public actual fun Path.hardLinkTo(target: Path): EmptyResult<FileError> {
    (pathError() ?: target.pathError())?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    if (CreateHardLinkW(toString(), target.toString(), null) != 0) return Ok(Unit)
    val code = GetLastError()
    if (code == ERROR_ALREADY_EXISTS.convert<UInt>()) return Err(alreadyExists())
    return Err(FileError.WriteFailed(this, IoCause.ofWindowsError(code), IOException("$this: windows error $code")))
}

// The C runtime keeps only whether the owner may write, as the read-only flag.
public actual fun Path.chmod(bits: Int): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    val mode = if (bits and OWNER_WRITE != 0) S_IREAD or S_IWRITE else S_IREAD
    if (chmod(toString(), mode.convert()) == 0) return Ok(Unit)
    val code = errno
    if (code == ENOENT) return Err(FileError.NotFound(this))
    return Err(FileError.WriteFailed(this, IoCause.ofErrno(code), IOException("$this: errno $code")))
}

public actual fun Path.mkfifo(): EmptyResult<FileError> {
    pathError()?.let { return Err(it) }
    parentsMade()?.let { return Err(it) }
    return Err(FileError.WriteFailed(this, IoCause.Other, UnsupportedOperationException("Windows has no FIFOs")))
}

private const val OWNER_WRITE = 0b010_000_000
