@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.windows.CloseHandle
import platform.windows.DWORDVar
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_APPEND_DATA
import platform.windows.FILE_BEGIN
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_READ_DATA
import platform.windows.FILE_STANDARD_INFO
import platform.windows.FILE_TYPE_DISK
import platform.windows.GetFileInformationByHandleEx
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.LARGE_INTEGER
import platform.windows.OPEN_ALWAYS
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.SetFilePointerEx
import platform.windows._FILE_INFO_BY_HANDLE_CLASS.FileStandardInfo

// FILE_APPEND_DATA without FILE_WRITE_DATA is Windows' append mode: every WriteFile lands at the end, whole. Read
// access lets lastByte read the open file; a file this process may only write is still appended to. Windows has no
// POSIX permissions, so [permissions] and [newMode] are ignored.
internal actual fun openAppendFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): Result<AppendFile, FileError> {
    val handle = openHandle(path, FILE_APPEND_DATA.convert<UInt>() or FILE_READ_DATA.convert<UInt>(), OPEN_ALWAYS)
        ?: if (GetLastError() == ERROR_ACCESS_DENIED.convert<UInt>()) {
            openHandle(path, FILE_APPEND_DATA.convert(), OPEN_ALWAYS)
        } else {
            null
        }
        ?: return Err(writeFailed(path, windowsFailure(GetLastError(), path)))
    if (GetFileType(handle) != FILE_TYPE_DISK.convert<UInt>()) {
        CloseHandle(handle)
        return Err(FileError.NotRegularFile(path, FileType.Other))
    }
    val identity = handle.fileKey() ?: run {
        val error = GetLastError()
        CloseHandle(handle)
        return Err(writeFailed(path, windowsFailure(error, path)))
    }
    return Ok(HandleAppendFile(path, handle, identity))
}

private class HandleAppendFile(
    override val path: Path,
    private val handle: HANDLE,
    private val opened: FileKey,
) : AppendFile {
    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.Closed(path))
        if (bytes.isEmpty()) return Ok(Unit)
        return writeFully(handle, bytes, path)?.let { Err(writeFailed(path, it)) } ?: Ok(Unit)
    }

    // The handle shares delete access, so the file can be deleted while open: pending until closed, or at once.
    override fun state(): Result<AppendState, FileError> {
        if (closed) return Ok(AppendState.Closed)
        val deleted = memScoped {
            val info = alloc<FILE_STANDARD_INFO>()
            if (!readStandardInfo(info)) return Err(inaccessible(path, windowsFailure(GetLastError(), path)))
            info.DeletePending.toInt() != 0 || info.NumberOfLinks == 0u
        }
        if (deleted) return Ok(AppendState.Deleted)
        return when (val now = fileIdAt(path)) {
            is Lookup.Found -> Ok(if (now.id == opened) AppendState.Same else AppendState.Moved)
            Lookup.Missing -> Ok(AppendState.Moved)
            is Lookup.Failed -> Err(inaccessible(path, windowsFailure(now.code, path)))
        }
    }

    /** Fills [info] for the open file; false when Windows refuses, with the reason in GetLastError. */
    private fun readStandardInfo(info: FILE_STANDARD_INFO): Boolean =
        GetFileInformationByHandleEx(handle, FileStandardInfo, info.ptr, sizeOf<FILE_STANDARD_INFO>().convert()) != 0

    override fun lastByte(): Result<Byte?, FileError> {
        if (closed) return Err(FileError.Closed(path))
        return memScoped {
            val info = alloc<FILE_STANDARD_INFO>()
            if (!readStandardInfo(info)) return@memScoped Err(inaccessible(path, windowsFailure(GetLastError(), path)))
            val length = info.EndOfFile.QuadPart
            if (length <= 0L) return@memScoped Ok(null)
            // Appends land at the end whatever the pointer says, so moving it for this read changes none of them.
            val end = cValue<LARGE_INTEGER> { QuadPart = length - 1 }
            if (SetFilePointerEx(handle, end, null, FILE_BEGIN.convert()) == 0) {
                return@memScoped Err(inaccessible(path, windowsFailure(GetLastError(), path)))
            }
            val byte = alloc<ByteVar>()
            val count = alloc<DWORDVar>()
            if (ReadFile(handle, byte.ptr, 1.convert(), count.ptr, null) == 0 || count.value != 1u) {
                Err(inaccessible(path, windowsFailure(GetLastError(), path)))
            } else {
                Ok(byte.value)
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        CloseHandle(handle)
    }
}

private sealed interface Lookup {
    data class Found(val id: FileKey) : Lookup

    data object Missing : Lookup

    /** Windows refused the lookup with the error [code]. */
    data class Failed(val code: UInt) : Lookup
}

/** What [path] names now: a file, nothing, or why it could not be looked up. */
private fun fileIdAt(path: Path): Lookup {
    // Attributes alone, shared every way, so this never blocks a writer, a reader or a rename.
    val handle = openHandle(path, FILE_READ_ATTRIBUTES.convert(), OPEN_EXISTING)
    if (handle == null) {
        val error = GetLastError()
        val missing = error == ERROR_FILE_NOT_FOUND.convert<UInt>() || error == ERROR_PATH_NOT_FOUND.convert<UInt>()
        return if (missing) Lookup.Missing else Lookup.Failed(error)
    }
    try {
        return handle.fileKey()?.let { Lookup.Found(it) } ?: Lookup.Failed(GetLastError())
    } finally {
        CloseHandle(handle)
    }
}
