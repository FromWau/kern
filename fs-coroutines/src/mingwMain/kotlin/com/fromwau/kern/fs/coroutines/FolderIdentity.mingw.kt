package com.fromwau.kern.fs.coroutines

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetFileInformationByHandle
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING

/** The volume and file index Windows keeps for the folder, which a folder renamed into its place does not share. */
@OptIn(ExperimentalForeignApi::class)
internal actual fun Path.folderIdentity(): Any? {
    val folder = CreateFileW(
        toString(),
        FILE_READ_ATTRIBUTES.convert(),
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).convert(),
        null,
        OPEN_EXISTING.convert(),
        FILE_FLAG_BACKUP_SEMANTICS.convert(),
        null,
    )
    if (folder == null || folder == INVALID_HANDLE_VALUE) return null
    return try {
        memScoped {
            val info = alloc<BY_HANDLE_FILE_INFORMATION>()
            if (GetFileInformationByHandle(folder, info.ptr) == 0) {
                null
            } else {
                Triple(info.dwVolumeSerialNumber, info.nFileIndexHigh, info.nFileIndexLow)
            }
        }
    } finally {
        val _ = CloseHandle(folder)
    }
}
