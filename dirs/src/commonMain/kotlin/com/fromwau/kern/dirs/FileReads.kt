package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString

/** Whether anything is at this path, following symlinks. Also `false` when the path cannot be looked up. */
public fun Path.exists(): Boolean = SystemFileSystem.exists(this)

/**
 * The whole file as UTF-8 text; bytes that are not valid UTF-8 read as U+FFFD. A directory, FIFO, device or
 * socket is [FileError.NotRegularFile] and is never opened, since opening a FIFO blocks until something
 * writes to it.
 *
 * @param maxBytes the largest file read, in bytes; a larger one is [FileError.TooLarge]. Null reads the whole
 *   file, however large.
 */
public fun Path.readText(maxBytes: Long? = null): Result<String, FileError> = metadata().flatMap { metadata ->
    when {
        !metadata.isRegularFile -> Err(FileError.NotRegularFile(this, metadata.type))
        maxBytes != null && metadata.size > maxBytes -> Err(FileError.TooLarge(this, maxBytes, metadata.size))
        else -> readWhole(maxBytes)
    }
}

private fun Path.readWhole(maxBytes: Long?): Result<String, FileError> = try {
    SystemFileSystem
        .source(this)
        .buffered()
        .use { source ->
            // The file can grow or be replaced after the size check, so the limit bounds the read as well.
            if (maxBytes != null && maxBytes < Long.MAX_VALUE && source.request(maxBytes + 1)) {
                Err(FileError.TooLarge(this, maxBytes, maxBytes + 1))
            } else {
                Ok(source.readString())
            }
        }
} catch (e: Exception) {
    Err(FileError.Inaccessible(this, e.reason))
}

/**
 * This directory's entries, as full paths in name order. An entry is listed as it is, so a symlink appears as
 * itself and is not followed. A file, FIFO, device or socket at this path is [FileError.NotADirectory].
 */
public fun Path.list(): Result<List<Path>, FileError> = metadata().flatMap { metadata ->
    if (!metadata.isDirectory) {
        Err(FileError.NotADirectory(this, metadata.type))
    } else {
        try {
            val entries = SystemFileSystem.list(this).sortedBy { it.name }
            // An empty listing is also how the JVM and Android report a directory they may not read.
            val denial = if (entries.isEmpty()) listDenial(this) else null
            if (denial == null) Ok(entries) else Err(FileError.Inaccessible(this, denial.reason))
        } catch (e: Exception) {
            Err(FileError.Inaccessible(this, e.reason))
        }
    }
}

/** Metadata for this path, following symlinks; [FileError.NotFound] only when nothing is really there. */
internal fun Path.metadata(): Result<FileMetadata, FileError> {
    // kotlinx-io answers null both for a missing path and for one it may not look at, and on Linux and Windows
    // throws for every errno but ENOENT. Neither says which, so anything but a straight answer goes to the probe.
    val found = try {
        SystemFileSystem.metadataOrNull(this)
    } catch (_: Exception) {
        null
    }
    if (found != null) return Ok(found)

    return when (val probe = probePath(this)) {
        is PathProbe.Present -> Ok(probe.metadata)
        PathProbe.Absent -> Err(FileError.NotFound(this))
        is PathProbe.Denied -> Err(FileError.Inaccessible(this, probe.reason))
    }
}
