package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.io.files.Path
import platform.linux.SYS_renameat2
import platform.posix.EEXIST
import platform.posix.EINVAL
import platform.posix.ENOSYS
import platform.posix.errno
import platform.posix.syscall

internal actual fun renameNoReplace(
    from: Path,
    to: Path,
): NoReplaceRename = renameNoReplaceAt(WORKING_DIRECTORY, from.toString(), to.toString(), about = from)

// The headers Kotlin/Native builds against predate glibc's renameat2 wrapper, so the system call is made directly.
@OptIn(ExperimentalForeignApi::class)
internal actual fun renameNoReplaceAt(
    folder: Int,
    from: String,
    to: String,
    about: Path,
): NoReplaceRename = memScoped {
    val renamed = syscall(
        SYS_renameat2.toLong(),
        folder.toLong(),
        from.cstr.ptr,
        folder.toLong(),
        to.cstr.ptr,
        RENAME_NOREPLACE,
    )
    if (renamed == 0L) return@memScoped NoReplaceRename.Renamed
    when (errno) {
        EEXIST -> NoReplaceRename.TargetTaken
        // A kernel older than 3.15, or a file system that does not take the flag.
        ENOSYS, EINVAL -> NoReplaceRename.Unavailable
        else -> NoReplaceRename.Failed(errnoFailure(about = about))
    }
}

private const val RENAME_NOREPLACE = 1L
