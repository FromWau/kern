package com.fromwau.kern.fs

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.EDQUOT
import platform.posix.ELOOP
import platform.posix.rmdir
import platform.posix.unlink

internal actual fun platformErrnoValue(name: Errno): Int? = when (name) {
    Errno.EDQUOT -> EDQUOT
    Errno.ELOOP -> ELOOP
    else -> null
}

// kotlinx-io's native failure carries only a message, so the removal is tried again where errno says why.
internal actual fun whyNotDeleted(path: Path, failure: Exception): Exception? {
    val folder = SystemFileSystem.metadataOrNull(path)?.isDirectory == true
    val removed = if (folder) rmdir(path.toString()) else unlink(path.toString())
    return if (removed == 0) null else errnoFailure(about = path)
}
