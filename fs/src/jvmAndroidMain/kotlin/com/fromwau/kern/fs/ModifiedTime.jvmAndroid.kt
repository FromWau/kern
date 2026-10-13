package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.time.Instant

internal actual fun lastModified(path: Path): Instant? = try {
    val time = Files.getLastModifiedTime(Paths.get(path.toString())).toInstant()
    Instant.fromEpochSeconds(time.epochSecond, time.nano)
} catch (_: Exception) {
    null
}
