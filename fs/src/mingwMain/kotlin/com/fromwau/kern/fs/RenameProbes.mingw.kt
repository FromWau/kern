package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_WRITE
import platform.windows.GetFileInformationByHandle
import platform.windows.GetLastError
import platform.windows.HANDLE
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

internal actual fun isSameFile(
    first: Path,
    second: Path,
): Boolean {
    val key = fileKeyOrNull(first) ?: return false
    return key == fileKeyOrNull(second)
}

@OptIn(ExperimentalForeignApi::class)
internal fun syncFile(path: Path): Exception? {
    val handle = openExisting(path, access = GENERIC_WRITE.convert()) ?: return windowsFailure(GetLastError(), path)
    val failure = if (FlushFileBuffers(handle) != 0) null else windowsFailure(GetLastError(), path)
    CloseHandle(handle)
    return failure
}

// Windows has no call that syncs a directory's entries.
internal actual fun syncDirectory(directory: Path): Exception? = null

// Whether kotlinx-io's mingw rename replaces an existing file is unverified, so no write relies on it.
internal actual val renameReplacesAtomically: Boolean = false

/**
 * Opens the file or folder at [path], following links, with [access]; null when it cannot be opened, with the reason
 * in `GetLastError`. Without backup semantics Windows opens no folder.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun openExisting(
    path: Path,
    access: UInt,
): HANDLE? = openHandle(path, access, OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS.convert())
