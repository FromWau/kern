package com.fromwau.kern.fs.coroutines

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual fun Path.folderIdentity(): Any? {
    val path = toString()
    return memScoped {
        val info = alloc<stat>()
        if (stat(path, info.ptr) != 0) null else info.st_dev to info.st_ino
    }
}
