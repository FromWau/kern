package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_DISK_FULL
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_HANDLE_DISK_FULL
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.ERROR_WRITE_PROTECT

/** The failure a Win32 call on [about] reported through the error [code] `GetLastError` gave, led by that path. */
@OptIn(InternalKernApi::class)
internal fun windowsFailure(
    code: UInt,
    about: Path,
): OsCallFailure = OsCallFailure(IoCause.ofWindowsError(code), about, "windows error $code")

/** The cause the Win32 error [code] from `GetLastError` stands for, as every fs call that reports one types it. */
@InternalKernApi
@OptIn(ExperimentalForeignApi::class)
public fun IoCause.Companion.ofWindowsError(code: UInt): IoCause = when (code) {
    ERROR_ACCESS_DENIED.convert<UInt>() -> IoCause.Denied
    ERROR_FILE_NOT_FOUND.convert<UInt>(), ERROR_PATH_NOT_FOUND.convert<UInt>() -> IoCause.NotFound
    ERROR_WRITE_PROTECT.convert<UInt>() -> IoCause.ReadOnly
    ERROR_DISK_FULL.convert<UInt>(), ERROR_HANDLE_DISK_FULL.convert<UInt>() -> IoCause.NoSpace
    else -> IoCause.Other
}

// The C runtime's errno, which the posix calls kern makes on Windows set, is typed by the shared names alone.
internal actual fun platformErrnoValue(name: Errno): Int? = null
