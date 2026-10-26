package com.fromwau.kern.dirs

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path

internal actual fun isPrivateFolder(path: Path): Boolean = try {
    val info = Os.stat(path.toString())
    OsConstants.S_ISDIR(info.st_mode) && info.st_uid == Os.geteuid() && (info.st_mode and GROUP_AND_OTHER_BITS) == 0
} catch (_: ErrnoException) {
    false
}
