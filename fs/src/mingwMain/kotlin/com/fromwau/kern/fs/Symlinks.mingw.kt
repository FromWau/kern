package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKStringFromUtf16
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import platform.windows.CloseHandle
import platform.windows.CreateSymbolicLinkW
import platform.windows.DWORDVar
import platform.windows.DeleteFileW
import platform.windows.DeviceIoControl
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.ERROR_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_FLAG_OPEN_REPARSE_POINT
import platform.windows.FSCTL_GET_REPARSE_POINT
import platform.windows.GetFileAttributesW
import platform.windows.GetFinalPathNameByHandleW
import platform.windows.GetLastError
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.MAXIMUM_REPARSE_DATA_BUFFER_SIZE
import platform.windows.MOVEFILE_REPLACE_EXISTING
import platform.windows.MoveFileExW
import platform.windows.OPEN_EXISTING
import platform.windows.RemoveDirectoryW
import platform.windows.SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE
import platform.windows.SYMBOLIC_LINK_FLAG_DIRECTORY
import platform.windows.WCHARVar

// The longest path the wide Windows APIs take, in characters.
private const val LONGEST_PATH = 32_768

// A symlink and a junction are both reparse points, and either can lead a walk back up.
internal actual fun isSymlink(path: Path): Boolean = try {
    ownFileTypeAt(path) == FileType.Symlink
} catch (_: Exception) {
    false
}

// Unverified on Windows. The link's own reparse data is read rather than where it leads, and a relative target stays
// relative. A write follows a dangling link through this; one whose target exists it writes through in place.
@OptIn(ExperimentalForeignApi::class)
internal actual fun readLink(path: Path): Path {
    val flags = FILE_FLAG_OPEN_REPARSE_POINT.convert<UInt>() or FILE_FLAG_BACKUP_SEMANTICS.convert<UInt>()
    val handle = openHandle(path, 0u, OPEN_EXISTING, flags) ?: throw windowsFailure(GetLastError(), path)
    try {
        val data = ByteArray(MAXIMUM_REPARSE_DATA_BUFFER_SIZE)
        val filled = memScoped {
            val returned = alloc<DWORDVar>()
            val done = data.usePinned { pinned ->
                DeviceIoControl(
                    handle,
                    FSCTL_GET_REPARSE_POINT.convert(),
                    null,
                    0u,
                    pinned.addressOf(0),
                    data.size.convert(),
                    returned.ptr,
                    null,
                )
            }
            if (done == 0) throw windowsFailure(GetLastError(), path)
            returned.value.toInt()
        }
        val target = linkTargetOf(data, filled)
            ?: throw OsCallFailure(IoCause.Other, path, "reparse data names no link target")
        return Path(target)
    } finally {
        CloseHandle(handle)
    }
}

// A link to a folder is removed as a folder, which DeleteFileW refuses as access denied.
@OptIn(ExperimentalForeignApi::class)
internal actual fun deleteLink(path: Path): Exception? {
    val name = path.toString()
    if (DeleteFileW(name) != 0) return null
    val fileError = GetLastError()
    if (fileError != ERROR_ACCESS_DENIED.convert<UInt>()) return windowsFailure(fileError, path)
    if (RemoveDirectoryW(name) != 0) return null
    val folderError = GetLastError()
    // Not a folder after all, so the file removal's refusal is the one that explains it.
    val code = if (folderError == ERROR_DIRECTORY.convert<UInt>()) fileError else folderError
    return windowsFailure(code, path)
}

// Unverified on Windows, as is whether it replaces a link at the target as a link.
@OptIn(ExperimentalForeignApi::class)
internal actual fun moveLink(
    path: Path,
    target: Path,
): Exception? = if (MoveFileExW(path.toString(), target.toString(), MOVEFILE_REPLACE_EXISTING.convert()) != 0) {
    null
} else {
    windowsFailure(GetLastError(), path)
}

// Unverified on Windows. A link to a folder has to say so when it is made, so the target is looked up from the link's
// folder; one that does not exist yet is made as a link to a file.
@OptIn(ExperimentalForeignApi::class)
internal actual fun createLink(
    path: Path,
    target: Path,
): Exception? {
    val attributes = GetFileAttributesW(linkedPath(path, target).toString())
    val isFolder = attributes != INVALID_FILE_ATTRIBUTES && (attributes and FILE_ATTRIBUTE_DIRECTORY.toUInt()) != 0u
    // Lets Developer Mode make links without elevation.
    val unprivileged = SYMBOLIC_LINK_FLAG_ALLOW_UNPRIVILEGED_CREATE.convert<UInt>()
    val flags = unprivileged or if (isFolder) SYMBOLIC_LINK_FLAG_DIRECTORY.convert<UInt>() else 0u
    // The target is stored as written, and kotlinx-io joins paths with `/`, which a link's target does not split on.
    val stored = target.toString().replace('/', '\\')
    if (CreateSymbolicLinkW(path.toString(), stored, flags).toInt() != 0) return null
    return windowsFailure(GetLastError(), path)
}

// kotlinx-io resolves with GetFullPathNameA here, which does not follow links, so the handle is asked instead.
@OptIn(ExperimentalForeignApi::class)
internal actual fun realPath(path: Path): Path {
    val handle = openExisting(path, access = 0u) ?: throw windowsFailure(GetLastError(), path)
    try {
        return memScoped {
            val buffer = allocArray<WCHARVar>(LONGEST_PATH)
            val length = GetFinalPathNameByHandleW(handle, buffer, LONGEST_PATH.convert(), 0u)
            if (length == 0u || length >= LONGEST_PATH.convert<UInt>()) throw windowsFailure(GetLastError(), path)
            Path(withoutVerbatimPrefix(buffer.toKStringFromUtf16()))
        }
    } finally {
        CloseHandle(handle)
    }
}
