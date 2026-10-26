@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.files.Path
import platform.posix.chmod
import platform.posix.fchmod

internal actual fun setPermissionBits(
    path: Path,
    bits: Int,
): Int = chmod(path.toString(), bits.convert())

internal actual fun setDescriptorPermissionBits(
    descriptor: Int,
    bits: Int,
): Int = fchmod(descriptor, bits.convert())
