package com.fromwau.kern.fs

import kotlinx.io.files.Path
import platform.posix.EEXIST
import platform.posix.ENOTSUP
import platform.posix.RENAME_EXCL
import platform.posix.errno
import platform.posix.renameatx_np

internal actual fun renameNoReplace(
    from: Path,
    to: Path,
): NoReplaceRename = renameNoReplaceAt(WORKING_DIRECTORY, from.toString(), to.toString(), about = from)

internal actual fun renameNoReplaceAt(
    folder: Int,
    from: String,
    to: String,
    about: Path,
): NoReplaceRename {
    if (renameatx_np(folder, from, folder, to, RENAME_EXCL.toUInt()) == 0) return NoReplaceRename.Renamed
    return when (errno) {
        EEXIST -> NoReplaceRename.TargetTaken
        // A file system that does not take the flag.
        ENOTSUP -> NoReplaceRename.Unavailable
        else -> NoReplaceRename.Failed(errnoFailure(about = about))
    }
}
