package com.fromwau.kern.dirs

import com.fromwau.kern.result.Result
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.ERANGE
import platform.posix._SC_GETPW_R_SIZE_MAX
import platform.posix.getpwuid_r
import platform.posix.getuid
import platform.posix.passwd
import platform.posix.sysconf

/**
 * @param strict whether a blank variable is [DirsError.Blank], one set to something other than an absolute path
 *   [DirsError.NotAbsolute], and an `XDG_RUNTIME_DIR` that names no private folder [DirsError.NotPrivate], rather than
 *   listed in [BaseDirs.ignored].
 */
public actual class BaseDirsFactory(private val strict: Boolean = false) {
    public actual fun create(): Result<BaseDirs, DirsError> = linuxBaseDir(::env, ::accountHome, strict)
}

/** This user's home from the account database, when it names an absolute path. */
@OptIn(ExperimentalForeignApi::class)
private fun accountHome(): String? {
    var bufferSize = sysconf(_SC_GETPW_R_SIZE_MAX).takeIf { it > 0 } ?: 1024L
    while (bufferSize <= LARGEST_ACCOUNT_BUFFER) {
        memScoped {
            val entry = alloc<passwd>()
            val found = alloc<CPointerVar<passwd>>()
            val buffer = allocArray<ByteVar>(bufferSize)
            when (getpwuid_r(getuid(), entry.ptr, buffer, bufferSize.convert(), found.ptr)) {
                0 -> return found.value
                    ?.pointed
                    ?.pw_dir
                    ?.toKString()
                    ?.asRoot()
                ERANGE -> bufferSize *= 2
                else -> return null
            }
        }
    }
    return null
}

/** Far more than any account entry needs, so a lookup that still asks for more is not going to fit. */
private const val LARGEST_ACCOUNT_BUFFER = 1L shl 20
