package com.fromwau.kern.fs

import platform.posix.openat

internal actual fun openAt(
    folder: Int,
    name: String,
    flags: Int,
    mode: Int,
): Int = openat(folder, name, flags, mode)
