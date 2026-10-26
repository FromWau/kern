@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetFileInformationByHandle
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.WriteFile

// Shared every way, so a handle kern holds never blocks a writer, a reader, a rename or a delete.
internal const val SHARE_ALL = FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE

/**
 * Opens [path] with [access], as [disposition] says, with [flags], shared every way; null when Windows refuses, with
 * the reason in `GetLastError`.
 */
internal fun openHandle(
    path: Path,
    access: UInt,
    disposition: Int,
    flags: UInt = FILE_ATTRIBUTE_NORMAL.convert(),
): HANDLE? {
    val handle = CreateFileW(path.toString(), access, SHARE_ALL.convert(), null, disposition.convert(), flags, null)
    return handle.takeUnless { it == null || it == INVALID_HANDLE_VALUE }
}

/** What tells one file from another on Windows: the volume it is on and its index there, its device and inode. */
internal data class FileKey(
    val volume: UInt,
    val indexHigh: UInt,
    val indexLow: UInt,
)

/** The [FileKey] of the file open here, or null when Windows refuses, with the reason in `GetLastError`. */
internal fun HANDLE.fileKey(): FileKey? = memScoped {
    val info = alloc<BY_HANDLE_FILE_INFORMATION>()
    if (GetFileInformationByHandle(this@fileKey, info.ptr) == 0) return@memScoped null
    FileKey(info.dwVolumeSerialNumber, info.nFileIndexHigh, info.nFileIndexLow)
}

/** Writes all of [bytes] through [handle], open on [path], answering why it could not. */
internal fun writeFully(
    handle: HANDLE,
    bytes: ByteArray,
    path: Path,
): Exception? {
    if (bytes.isEmpty()) return null
    return memScoped {
        val written = alloc<DWORDVar>()
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < bytes.size) {
                val left = bytes.size - offset
                if (WriteFile(handle, pinned.addressOf(offset), left.convert(), written.ptr, null) == 0) {
                    return@memScoped windowsFailure(GetLastError(), path)
                }
                // Asked again, it would take nothing again, forever.
                if (written.value == 0u) {
                    return@memScoped OsCallFailure(IoCause.Other, path, "wrote none of the $left bytes left")
                }
                offset += written.value.toInt()
            }
        }
        null
    }
}
