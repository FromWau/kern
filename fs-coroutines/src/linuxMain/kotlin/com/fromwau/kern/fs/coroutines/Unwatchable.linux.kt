package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.PseudoFileSystem
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.linux.statfs

// The f_type magic numbers of linux/magic.h.
private const val PROC_SUPER_MAGIC = 0x9fa0L
private const val SYSFS_MAGIC = 0x62656572L

@OptIn(ExperimentalForeignApi::class)
internal actual fun pseudoFileSystemOf(path: Path): PseudoFileSystem? = memScoped {
    val info = alloc<statfs>()
    if (generateSequence(path) { it.parent }.none { statfs(it.toString(), info.ptr) == 0 }) return null
    when (info.f_type) {
        PROC_SUPER_MAGIC -> PseudoFileSystem.Proc
        SYSFS_MAGIC -> PseudoFileSystem.Sys
        else -> null
    }
}
