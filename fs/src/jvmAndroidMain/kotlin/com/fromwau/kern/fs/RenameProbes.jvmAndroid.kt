package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

internal actual fun isSameFile(
    first: Path,
    second: Path,
): Boolean = try {
    Files.isSameFile(Paths.get(first.toString()), Paths.get(second.toString()))
} catch (_: Exception) {
    false
}

internal actual fun syncDirectory(directory: Path): Exception? {
    // Windows refuses to open a directory as a channel, and has no directory sync to offer anyway.
    if (!hasUnixView) return null
    return try {
        FileChannel.open(Paths.get(directory.toString()), StandardOpenOption.READ).use { it.force(true) }
        null
    } catch (e: Exception) {
        e
    }
}

// kotlinx-io moves on the JVM with Files.move and ATOMIC_MOVE.
internal actual val renameReplacesAtomically: Boolean = true
