package com.fromwau.kern.fs

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import kotlinx.io.files.Path

// Os.mkdir rather than java.nio, for the reason FilePermissions.android.kt gives.
internal actual fun makeFolder(
    path: Path,
    mode: Int,
): Boolean = try {
    Os.mkdir(path.toString(), mode)
    true
} catch (e: ErrnoException) {
    if (e.errno == OsConstants.EEXIST && isFolderAt(path)) false else throw e.typed(path)
}
