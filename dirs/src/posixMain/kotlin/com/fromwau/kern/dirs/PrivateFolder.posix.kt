package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.geteuid
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun isPrivateFolder(path: Path): Boolean = memScoped {
    val info = alloc<stat>()
    if (stat(path.toString(), info.ptr) != 0) return@memScoped false
    val mode = info.st_mode.toInt()
    // geteuid, not getuid: access is checked against the effective user, while the account home is the real user's.
    (mode and S_IFMT) == S_IFDIR && info.st_uid == geteuid() && (mode and GROUP_AND_OTHER_BITS) == 0
}
