package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.FileTime
import kotlin.time.Instant

@InternalKernApi
public actual fun setLastModified(
    path: Path,
    time: Instant,
) {
    val stamp = java.time.Instant.ofEpochSecond(time.epochSeconds, time.nanosecondsOfSecond.toLong())
    Files.setLastModifiedTime(Paths.get(path.toString()), FileTime.from(stamp))
}
