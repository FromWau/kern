@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_APPEND_DATA
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_BEGIN
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_READ_DATA
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FILE_STANDARD_INFO
import platform.windows.FILE_TYPE_DISK
import platform.windows.GetFileInformationByHandle
import platform.windows.GetFileInformationByHandleEx
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LARGE_INTEGER
import platform.windows.OPEN_ALWAYS
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.SetFilePointerEx
import platform.windows.WriteFile
import platform.windows._FILE_INFO_BY_HANDLE_CLASS.FileStandardInfo

private const val SHARE_ALL = FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE

// FILE_APPEND_DATA without FILE_WRITE_DATA is Windows' append mode: every WriteFile lands at the end, whole. Read
// access lets lastByte read the open file; a file this process may only write is still appended to.
internal actual fun openAppendFile(path: Path): Result<AppendFile, FileError> {
    val handle = openHandle(path, FILE_APPEND_DATA.convert<UInt>() or FILE_READ_DATA.convert<UInt>(), OPEN_ALWAYS)
        ?: if (GetLastError() == ERROR_ACCESS_DENIED.convert<UInt>()) {
            openHandle(path, FILE_APPEND_DATA.convert(), OPEN_ALWAYS)
        } else {
            null
        }
        ?: return Err(FileError.WriteFailed(path, windowsError(GetLastError())))
    if (GetFileType(handle) != FILE_TYPE_DISK.convert<UInt>()) {
        CloseHandle(handle)
        return Err(FileError.NotRegularFile(path, FileType.Other))
    }
    val identity = fileIdOf(handle) ?: run {
        val error = GetLastError()
        CloseHandle(handle)
        return Err(FileError.WriteFailed(path, windowsError(error)))
    }
    return Ok(HandleAppendFile(path, handle, identity))
}

private class HandleAppendFile(
    override val path: Path,
    private val handle: HANDLE,
    private val opened: FileId,
) : AppendFile {
    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.WriteFailed(path, CLOSED))
        if (bytes.isEmpty()) return Ok(Unit)
        return memScoped {
            val written = alloc<DWORDVar>()
            bytes.usePinned { pinned ->
                var offset = 0
                while (offset < bytes.size) {
                    val ok = WriteFile(
                        handle,
                        pinned.addressOf(offset),
                        (bytes.size - offset).convert(),
                        written.ptr,
                        null,
                    )
                    if (ok == 0) return@memScoped Err(FileError.WriteFailed(path, windowsError(GetLastError())))
                    offset += written.value.toInt()
                }
            }
            Ok(Unit)
        }
    }

    // The handle shares delete access, so the file can be deleted while open: pending until closed, or at once.
    override fun state(): AppendState {
        if (closed) return AppendState.Unknown
        val deleted = memScoped {
            val info = alloc<FILE_STANDARD_INFO>()
            if (!readStandardInfo(info)) null else info.DeletePending.toInt() != 0 || info.NumberOfLinks == 0u
        }
        return when (deleted) {
            null -> AppendState.Unknown
            true -> AppendState.Deleted
            false -> when (val now = fileIdAt(path)) {
                null -> AppendState.Unknown
                is Lookup.Found -> if (now.id == opened) AppendState.Same else AppendState.Moved
                Lookup.Missing -> AppendState.Moved
            }
        }
    }

    /** Fills [info] for the open file; false when Windows refuses, with the reason in GetLastError. */
    private fun readStandardInfo(info: FILE_STANDARD_INFO): Boolean =
        GetFileInformationByHandleEx(handle, FileStandardInfo, info.ptr, sizeOf<FILE_STANDARD_INFO>().convert()) != 0

    override fun lastByte(): Result<Byte?, FileError> {
        if (closed) return Err(FileError.Inaccessible(path, CLOSED))
        return memScoped {
            val info = alloc<FILE_STANDARD_INFO>()
            if (!readStandardInfo(info)) return@memScoped Err(FileError.Inaccessible(path, windowsError(GetLastError())))
            val length = info.EndOfFile.QuadPart
            if (length <= 0L) return@memScoped Ok(null)
            // Appends land at the end whatever the pointer says, so moving it for this read changes none of them.
            val end = cValue<LARGE_INTEGER> { QuadPart = length - 1 }
            if (SetFilePointerEx(handle, end, null, FILE_BEGIN.convert()) == 0) {
                return@memScoped Err(FileError.Inaccessible(path, windowsError(GetLastError())))
            }
            val byte = alloc<ByteVar>()
            val count = alloc<DWORDVar>()
            if (ReadFile(handle, byte.ptr, 1.convert(), count.ptr, null) == 0 || count.value != 1u) {
                Err(FileError.Inaccessible(path, windowsError(GetLastError())))
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

private const val CLOSED = "the file was closed"

/** What NTFS tells files apart by: the volume and the file's index on it, the device and inode of Windows. */
private data class FileId(val volume: UInt, val indexHigh: UInt, val indexLow: UInt)

private sealed interface Lookup {
    data class Found(val id: FileId) : Lookup

    data object Missing : Lookup
}

private fun fileIdOf(handle: HANDLE?): FileId? = memScoped {
    val info = alloc<BY_HANDLE_FILE_INFORMATION>()
    if (GetFileInformationByHandle(handle, info.ptr) == 0) {
        null
    } else {
        FileId(info.dwVolumeSerialNumber, info.nFileIndexHigh, info.nFileIndexLow)
    }
}

/** What [path] names now: a file, nothing, or null when it could not be looked up. */
private fun fileIdAt(path: Path): Lookup? {
    // Attributes alone, shared every way, so this never blocks a writer, a reader or a rename.
    val handle = openHandle(path, FILE_READ_ATTRIBUTES.convert(), OPEN_EXISTING)
    if (handle == null) {
        val error = GetLastError()
        val missing = error == ERROR_FILE_NOT_FOUND.convert<UInt>() || error == ERROR_PATH_NOT_FOUND.convert<UInt>()
        return if (missing) Lookup.Missing else null
    }
    try {
        return fileIdOf(handle)?.let { Lookup.Found(it) }
    } finally {
        CloseHandle(handle)
    }
}

private fun openHandle(path: Path, access: UInt, disposition: Int): HANDLE? {
    val handle = CreateFileW(
        path.toString(),
        access,
        SHARE_ALL.convert(),
        null,
        disposition.convert(),
        FILE_ATTRIBUTE_NORMAL.convert(),
        null,
    )
    return if (handle == null || handle == INVALID_HANDLE_VALUE) null else handle
}
