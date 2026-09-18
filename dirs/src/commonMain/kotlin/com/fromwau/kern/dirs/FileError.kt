package com.fromwau.kern.dirs

import com.fromwau.kern.result.IError
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path

/** Why a file operation failed. Every case names the [path] it is about. */
public sealed interface FileError : IError {
    public val path: Path

    /**
     * Nothing is at [path]: it is missing, a symlink whose target is missing, or a path that runs through a file
     * and so cannot resolve. A path that could not be looked up is [Inaccessible] instead, on every target.
     */
    public data class NotFound(override val path: Path) : FileError

    /** [path] could not be read or looked up. [reason] is the error message, which differs per platform. */
    public data class Inaccessible(
        override val path: Path,
        val reason: String,
    ) : FileError

    /** A directory, FIFO, device or socket stands where a regular file was expected. */
    public data class NotRegularFile(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /** A file, FIFO, device or socket stands where a directory was expected. [type] is what is there instead. */
    public data class NotADirectory(
        override val path: Path,
        val type: FileType,
    ) : FileError

    /**
     * [path] holds at least [atLeastBytes] bytes, more than the caller's limit of [limitBytes]. It is a lower
     * bound: a read that crosses the limit stops there instead of measuring the rest.
     */
    public data class TooLarge(
        override val path: Path,
        val limitBytes: Long,
        val atLeastBytes: Long,
    ) : FileError

    /** Writing the file or creating a directory failed. [reason] is the error message, which differs per platform. */
    public data class WriteFailed(
        override val path: Path,
        val reason: String,
    ) : FileError

    /**
     * A [writeText] or [writeBytes] failed after emptying [path], and undoing it failed too, so [path] is damaged.
     * [backup] holds the old content, or is null when the file was new. [reason] is the write's error message.
     */
    public data class RestoreFailed(
        override val path: Path,
        val reason: String,
        val backup: Path?,
    ) : FileError
}

/** What is at a path, following symlinks. [Other] is a FIFO, a device or a socket. */
public enum class FileType {
    Regular,
    Directory,
    Other,
}

internal val FileMetadata.type: FileType
    get() = when {
        isRegularFile -> FileType.Regular
        isDirectory -> FileType.Directory
        else -> FileType.Other
    }

internal val Exception.reason: String
    get() = message ?: toString()
