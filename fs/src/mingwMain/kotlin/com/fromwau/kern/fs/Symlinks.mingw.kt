package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.io.files.Path
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DeleteFileW
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetFileAttributesW
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.GetLastError
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.RemoveDirectoryW
import platform.windows.WCHARVar

// The longest path the wide Windows APIs take, in characters.
private const val LONGEST_PATH = 32_768

// A symlink and a junction are both reparse points, and either can lead a walk back up.
internal actual fun isSymlink(path: Path): Boolean {
    val attributes = GetFileAttributesW(path.toString())
    return attributes != INVALID_FILE_ATTRIBUTES && (attributes and FILE_ATTRIBUTE_REPARSE_POINT.toUInt()) != 0u
}

// A link to a folder is removed as a folder, which DeleteFileW refuses as access denied.
@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteLink(path: Path): String? {
    val name = path.toString()
    if (DeleteFileW(name) != 0) return null
    val fileError = GetLastError()
    if (fileError != ERROR_ACCESS_DENIED.convert<UInt>()) return windowsError(fileError)
    if (RemoveDirectoryW(name) != 0) return null
    val folderError = GetLastError()
    // Not a folder after all, so the file removal's refusal is the one that explains it.
    return windowsError(if (folderError == ERROR_DIRECTORY.convert<UInt>()) fileError else folderError)
}

// kotlinx-io resolves with GetFullPathNameA here, which does not follow links, so the handle is asked instead.
@OptIn(ExperimentalForeignApi::class)
internal actual fun realPath(path: Path): Path {
    val handle = CreateFileW(
        path.toString(),
        0u,
        FILE_SHARE_READ.convert<UInt>() or FILE_SHARE_WRITE.convert<UInt>() or FILE_SHARE_DELETE.convert<UInt>(),
        null,
        OPEN_EXISTING.convert<UInt>(),
        // Without it a folder cannot be opened at all.
        FILE_FLAG_BACKUP_SEMANTICS.convert<UInt>(),
        null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) return path
    val resolved = memScoped {
        val buffer = allocArray<WCHARVar>(LONGEST_PATH)
        val length = GetFinalPathNameByHandleW(handle, buffer, LONGEST_PATH.convert(), 0u)
        if (length == 0u || length >= LONGEST_PATH.convert<UInt>()) null else buffer.toKStringFromUtf16()
    }
    CloseHandle(handle)
    return resolved?.let { Path(it) } ?: path
}
