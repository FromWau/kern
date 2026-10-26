@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.EEXIST
import platform.posix.errno

internal actual fun makeFolder(
    path: Path,
    mode: Int,
): Boolean {
    if (makeFolderAt(WORKING_DIRECTORY, path.toString(), mode) == 0) return true
    // Read before the lookup below, which can set errno again.
    val alreadyThere = errno == EEXIST
    val failure = errnoFailure(about = path)
    if (alreadyThere && isFolderAt(path)) return false
    throw failure
}
