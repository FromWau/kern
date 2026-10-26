@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod

internal actual fun setPermissionBits(
    path: Path,
    bits: Int,
): Int = chmod(path.toString(), bits.convert())

// A Windows file has no group to keep.
internal actual fun copyGroup(
    from: Path,
    to: Path,
): Boolean = true

// A Windows folder takes its access rules from the folder it is made in.
internal actual fun restrictToOwner(path: Path) = Unit
