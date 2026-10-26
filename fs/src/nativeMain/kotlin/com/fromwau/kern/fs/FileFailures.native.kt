package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.posix.EACCES
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.EPERM
import platform.posix.EROFS
import platform.posix.errno
import platform.posix.strerror

// kotlinx-io's native exceptions carry only a message, which is never read for a cause.
internal actual fun Throwable.platformCause(): IoCause = IoCause.Other

/**
 * The failure the last system call reported through `errno`, in the platform's words, led by [about], the file the
 * call was made on. Read it right after the call.
 */
@OptIn(ExperimentalForeignApi::class, InternalKernApi::class)
internal fun errnoFailure(about: Path): OsCallFailure {
    val code = errno
    return OsCallFailure(IoCause.ofErrno(code), about, strerror(code)?.toKString() ?: "errno $code")
}

/** The cause the `errno` value [code] stands for, as every fs call that reports one types it. */
@InternalKernApi
public fun IoCause.Companion.ofErrno(code: Int): IoCause = causeOfErrno(code) { name ->
    when (name) {
        Errno.EACCES -> EACCES
        Errno.EPERM -> EPERM
        Errno.ENOENT -> ENOENT
        Errno.EROFS -> EROFS
        Errno.ENOSPC -> ENOSPC
        Errno.EDQUOT, Errno.ELOOP -> platformErrnoValue(name)
    }
}

/** This platform's value for an `errno` name only some platforms have, or null where it has none. */
internal expect fun platformErrnoValue(name: Errno): Int?
