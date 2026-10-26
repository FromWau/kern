package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.windows.CreateDirectoryW
import platform.windows.ERROR_ALREADY_EXISTS
import platform.windows.GetLastError

// A Windows folder takes its access rules from the folder it is made in, so there is no mode to make it with.
@OptIn(ExperimentalForeignApi::class)
internal actual fun makeFolder(
    path: Path,
    mode: Int,
): Boolean {
    if (CreateDirectoryW(path.toString(), null) != 0) return true
    val code = GetLastError()
    if (code == ERROR_ALREADY_EXISTS.convert<UInt>() && isFolderAt(path)) return false
    throw windowsFailure(code, path)
}

internal actual fun whyNotDeleted(path: Path, failure: Exception): Exception? = failure
