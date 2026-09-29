package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.io.files.Path
import platform.posix.memset
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.ERROR_LOCK_VIOLATION
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
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
 * `LOCKFILE_FAIL_IMMEDIATELY` in a bounded retry rather than a blocking call, as the other actuals do: the
 * [withLock] promises a bounded wait. Windows names no holder, so [FileError.LockBusy] carries no pid.
 *
 * The handle is closed only after [block] returns, since closing it is what releases the lock.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withOsFileLock(
    lockFile: Path,
    timeoutMillis: Long,
    block: () -> T,
): OsLock<T> {
    val handle = CreateFileW(
        lockFile.toString(),
        GENERIC_READ.convert<UInt>() or GENERIC_WRITE.convert<UInt>(),
        FILE_SHARE_READ.convert<UInt>() or FILE_SHARE_WRITE.convert<UInt>(),
        null,
        OPEN_ALWAYS.convert<UInt>(),
        FILE_ATTRIBUTE_NORMAL.convert<UInt>(),
        null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) {
        return OsLock.Failed(windowsError(GetLastError()))
    }

    return try {
        when (val outcome = acquireWithin(handle, timeoutMillis)) {
            is LockOutcome.Acquired -> OsLock.Ran(block())
            is LockOutcome.Busy -> OsLock.Busy(holderPid = null)
            is LockOutcome.Failed -> OsLock.Failed(outcome.detail)
        }
    } finally {
        CloseHandle(handle)
    }
}

private sealed interface LockOutcome {
    data object Acquired : LockOutcome
    data object Busy : LockOutcome
    data class Failed(val detail: String) : LockOutcome
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun sleepMillis(millis: Long): Boolean {
    Sleep(millis.convert())
    return true
}

/** Polls for the lock until [timeoutMillis] has passed. A lock violation means held; anything else fails. */
@OptIn(ExperimentalForeignApi::class)
private fun acquireWithin(handle: HANDLE, timeoutMillis: Long): LockOutcome = memScoped {
    val request = alloc<OVERLAPPED>()
    val flags = LOCKFILE_EXCLUSIVE_LOCK.convert<UInt>() or LOCKFILE_FAIL_IMMEDIATELY.convert<UInt>()
    var waited = 0L
    var outcome: LockOutcome? = null
    while (outcome == null) {
        // LockFileEx reads the whole structure, and only a zeroed one asks to lock from offset zero.
        memset(request.ptr, 0, sizeOf<OVERLAPPED>().convert())
        val code = if (LockFileEx(handle, flags, 0u, WHOLE_FILE, WHOLE_FILE, request.ptr) != 0) 0u else GetLastError()
        outcome = when {
            code == 0u -> LockOutcome.Acquired
            code != ERROR_LOCK_VIOLATION.convert<UInt>() -> LockOutcome.Failed(windowsError(code))
            waited >= timeoutMillis -> LockOutcome.Busy
            else -> null
        }
        if (outcome == null) {
            sleepMillis(LOCK_RETRY_INTERVAL_MILLIS)
            waited += LOCK_RETRY_INTERVAL_MILLIS
        }
    }
    outcome
}

private fun windowsError(code: UInt): String = "windows error $code"
