@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod

internal actual fun setPermissionBits(
    path: Path,
    bits: Int,
): Int = chmod(path.toString(), bits.convert())
