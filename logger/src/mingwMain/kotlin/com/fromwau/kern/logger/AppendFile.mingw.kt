@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.io.IOException
import kotlinx.io.files.Path
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.FILE_APPEND_DATA
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetLastError
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_ALWAYS
import platform.windows.WriteFile

// FILE_APPEND_DATA without write access is Windows' append mode: every WriteFile lands at the end, whole.
internal actual fun openAppendFile(path: Path): AppendFile {
    val handle = CreateFileW(
        path.toString(),
        FILE_APPEND_DATA.convert(),
        (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).convert(),
        null,
        OPEN_ALWAYS.convert(),
        FILE_ATTRIBUTE_NORMAL.convert(),
        null,
    )
    if (handle == null || handle == INVALID_HANDLE_VALUE) throw IOException("$path: windows error ${GetLastError()}")
    return object : AppendFile {
        override fun append(bytes: ByteArray) {
            if (bytes.isEmpty()) return
            memScoped {
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
                        if (ok == 0) throw IOException("$path: windows error ${GetLastError()}")
                        offset += written.value.toInt()
                    }
                }
            }
        }

        override fun close() {
            CloseHandle(handle)
        }
    }
}
