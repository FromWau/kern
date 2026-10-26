@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs.test

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.mkfifo

internal actual fun setPermissionBits(
    path: Path,
    bits: Int,
): Int = chmod(path.toString(), bits.convert())

internal actual fun makeFifo(
    path: Path,
    mode: Int,
): Int = mkfifo(path.toString(), mode.convert())
