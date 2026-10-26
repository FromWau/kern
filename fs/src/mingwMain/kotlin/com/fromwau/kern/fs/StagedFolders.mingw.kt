package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.windows.ERROR_ALREADY_EXISTS
import platform.windows.ERROR_FILE_EXISTS
import platform.windows.GetLastError
import platform.windows.MoveFileExW

// Unverified on Windows: without MOVEFILE_REPLACE_EXISTING, a move onto an existing path is refused.
@OptIn(ExperimentalForeignApi::class)
internal actual fun renameNoReplace(
    from: Path,
    to: Path,
): NoReplaceRename {
    if (MoveFileExW(from.toString(), to.toString(), 0u) != 0) return NoReplaceRename.Renamed
    return when (val code = GetLastError()) {
        ERROR_ALREADY_EXISTS.toUInt(), ERROR_FILE_EXISTS.toUInt() -> NoReplaceRename.TargetTaken
        else -> NoReplaceRename.Failed(windowsFailure(code, about = from))
    }
}
