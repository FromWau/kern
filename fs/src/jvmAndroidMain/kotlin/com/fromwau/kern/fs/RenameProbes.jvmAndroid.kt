package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.channels.FileChannel
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/** Whether java.nio reads unix attributes such as `unix:uid` here, which Windows does not. */
internal val hasUnixView: Boolean = "unix" in FileSystems.getDefault().supportedFileAttributeViews()

internal actual fun syncFile(path: Path): String? = try {
    FileChannel.open(Paths.get(path.toString()), StandardOpenOption.WRITE).use { it.force(true) }
    null
} catch (e: Exception) {
    e.reason
}

internal actual fun syncDirectory(directory: Path): String? {
    // Windows refuses to open a directory as a channel, and has no directory sync to offer anyway.
    if (!hasUnixView) return null
    return try {
        FileChannel.open(Paths.get(directory.toString()), StandardOpenOption.READ).use { it.force(true) }
        null
    } catch (e: Exception) {
        e.reason
    }
}

// kotlinx-io moves on the JVM with Files.move and ATOMIC_MOVE.
internal actual val renameReplacesAtomically: Boolean = true
