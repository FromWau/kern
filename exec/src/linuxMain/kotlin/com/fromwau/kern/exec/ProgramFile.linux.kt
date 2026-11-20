@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.exec

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.X_OK
import platform.posix.access
import platform.posix.stat

internal actual fun programFile(path: Path): ProgramFile = memScoped {
    val status = alloc<stat>()
    val name = path.toString()
    when {
        stat(name, status.ptr) != 0 || status.st_mode.toInt() and S_IFMT != S_IFREG -> ProgramFile.Missing
        access(name, X_OK) != 0 -> ProgramFile.NotExecutable
        else -> ProgramFile.Executable
    }
}
