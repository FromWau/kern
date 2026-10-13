package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_WRITE
import platform.windows.GetFileInformationByHandle
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING

// Owner checks need the security APIs, which this does not call, so ownership is assumed: an Atomic write here can
// hand another user's file to this one.
@OptIn(ExperimentalForeignApi::class)
internal actual fun fileIdentity(path: Path): FileIdentity? {
    // No access rights: reading a file's attributes needs none, so a file this process may not read still answers.
    val handle = openExisting(path, access = 0u) ?: return null
    val links = memScoped {
        val info = alloc<BY_HANDLE_FILE_INFORMATION>()
        if (GetFileInformationByHandle(handle, info.ptr) != 0) info.nNumberOfLinks.toLong() else null
    }
    CloseHandle(handle)
    return links?.let { FileIdentity(linkCount = it, ownedByCurrentUser = true) }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun syncFile(path: Path): String? {
    val handle = openExisting(path, access = GENERIC_WRITE.convert()) ?: return windowsError(GetLastError())
    val reason = if (FlushFileBuffers(handle) != 0) null else windowsError(GetLastError())
    CloseHandle(handle)
    return reason
}

// Windows has no call that syncs a directory's entries.
internal actual fun syncDirectory(directory: Path): String? = null

// Whether kotlinx-io's mingw rename replaces an existing file is unverified, so no write relies on it.
internal actual val renameReplacesAtomically: Boolean = false

@OptIn(ExperimentalForeignApi::class)
private fun openExisting(
    path: Path,
    access: UInt,
): HANDLE? {
    val handle = CreateFileW(
        path.toString(),
        access,
        FILE_SHARE_READ.convert<UInt>() or FILE_SHARE_WRITE.convert<UInt>() or FILE_SHARE_DELETE.convert<UInt>(),
        null,
        OPEN_EXISTING.convert<UInt>(),
        FILE_ATTRIBUTE_NORMAL.convert<UInt>(),
        null,
    )
    return handle.takeUnless { it == null || it == INVALID_HANDLE_VALUE }
}
