package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.io.files.Path
import platform.posix.memset
import platform.windows.CloseHandle
import platform.windows.ERROR_LOCK_VIOLATION
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.LOCKFILE_EXCLUSIVE_LOCK
import platform.windows.LOCKFILE_FAIL_IMMEDIATELY
import platform.windows.LockFileEx
import platform.windows.OPEN_ALWAYS
import platform.windows.OVERLAPPED
import platform.windows.Sleep

// The whole possible range, so the lock covers a file of any length.
private const val WHOLE_FILE: UInt = 0xFFFFFFFFu

/**
 * `LockFileEx` with `LOCKFILE_EXCLUSIVE_LOCK`, which is the mandatory, process-wide lock Windows offers, so a
 * second holder is refused rather than passing through.
 *
 * `LOCKFILE_FAIL_IMMEDIATELY`, one try, as the other actuals do: the caller retries within its own bound.
 * Windows names no holder, so [FileError.LockBusy] carries no pid. A Windows file has no POSIX mode, so [permissions]
 * and [newMode] are ignored.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun tryOsLock(
    lockFile: Path,
    permissions: Int?,
    newMode: Int,
): OsLock {
    // Shared for delete too, since the lock file is deleted while it is still held.
    val handle = openHandle(lockFile, GENERIC_READ.convert<UInt>() or GENERIC_WRITE.convert<UInt>(), OPEN_ALWAYS)
        ?: return OsLock.NotOpened(windowsFailure(GetLastError(), lockFile))

    val code = memScoped {
        val request = alloc<OVERLAPPED>()
        // LockFileEx reads the whole structure, and only a zeroed one asks to lock from offset zero.
        memset(request.ptr, 0, sizeOf<OVERLAPPED>().convert())
        val flags = LOCKFILE_EXCLUSIVE_LOCK.convert<UInt>() or LOCKFILE_FAIL_IMMEDIATELY.convert<UInt>()
        if (LockFileEx(handle, flags, 0u, WHOLE_FILE, WHOLE_FILE, request.ptr) != 0) 0u else GetLastError()
    }
    if (code == 0u) {
        return OsLock.Held(key = handle.fileKey()) { CloseHandle(handle) }
    }
    CloseHandle(handle)
    return if (code == ERROR_LOCK_VIOLATION.convert<UInt>()) {
        OsLock.Busy(holderPid = null)
    } else {
        OsLock.Failed(windowsFailure(code, lockFile))
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sleepMillis(millis: Long): Boolean {
    Sleep(millis.convert())
    return true
}

// No access rights: reading a file's identity needs none.
@OptIn(ExperimentalForeignApi::class)
internal actual fun fileKeyOrNull(path: Path): Any? {
    val handle = openExisting(path, access = 0u) ?: return null
    val key = handle.fileKey()
    CloseHandle(handle)
    return key
}
