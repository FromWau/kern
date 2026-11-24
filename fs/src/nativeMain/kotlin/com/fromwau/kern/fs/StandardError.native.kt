package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush
import platform.posix.fputs
import platform.posix.stderr

@OptIn(ExperimentalForeignApi::class)
internal actual fun printToStandardError(text: String) {
    fputs("$text\n", stderr)
    fflush(stderr)
}
