package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.windows.GetFileAttributesExW
import platform.windows.WIN32_FILE_ATTRIBUTE_DATA
import platform.windows._GET_FILEEX_INFO_LEVELS
import kotlin.time.Instant

// FILETIME counts 100-nanosecond ticks since 1601-01-01, this many before the Unix epoch.
private const val TICKS_TO_UNIX_EPOCH = 116_444_736_000_000_000L
private const val TICKS_PER_SECOND = 10_000_000L
private const val NANOS_PER_TICK = 100L

@OptIn(ExperimentalForeignApi::class)
internal actual fun lastModified(path: Path): Instant? = memScoped {
    val data = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
    if (GetFileAttributesExW(path.toString(), _GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, data.ptr) == 0) {
        return@memScoped null
    }
    val written = data.ftLastWriteTime
    val ticks = (written.dwHighDateTime.toLong() shl 32 or written.dwLowDateTime.toLong()) - TICKS_TO_UNIX_EPOCH
    Instant.fromEpochSeconds(ticks.floorDiv(TICKS_PER_SECOND), ticks.mod(TICKS_PER_SECOND) * NANOS_PER_TICK)
}
