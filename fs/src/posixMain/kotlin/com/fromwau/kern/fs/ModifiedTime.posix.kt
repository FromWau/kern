@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.io.files.Path
import platform.posix.futimens
import platform.posix.timespec
import platform.posix.timeval
import platform.posix.utimes
import kotlin.time.Instant

/** `futimens` on [descriptor], setting its modification time to [time] and leaving its access time; 0 or -1. */
internal fun setDescriptorModifiedTime(
    descriptor: Int,
    time: Instant,
): Int = memScoped {
    val times = allocArray<timespec>(2)
    times[0].tv_nsec = OMIT_TIME.convert()
    times[1].tv_sec = time.epochSeconds.convert()
    times[1].tv_nsec = time.nanosecondsOfSecond.convert()
    futimens(descriptor, times)
}

// Linux lacks utimensat in its bindings, so posix uses utimes, which keeps microseconds.
@InternalKernApi
public actual fun setLastModified(
    path: Path,
    time: Instant,
) {
    val set = memScoped {
        val times = allocArray<timeval>(2)
        for (index in 0..1) {
            times[index].tv_sec = time.epochSeconds.convert()
            times[index].tv_usec = (time.nanosecondsOfSecond / NANOS_PER_MICRO).convert()
        }
        utimes(path.toString(), times)
    }
    if (set != 0) throw errnoFailure(about = path)
}

private const val NANOS_PER_MICRO = 1_000

/** The `tv_nsec` that tells `futimens` to leave that time as it is, a different number on each target. */
internal expect val OMIT_TIME: Long
