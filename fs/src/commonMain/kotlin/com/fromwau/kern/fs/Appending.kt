package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/**
 * Opens this file for appending, creating it and any missing parent folders, and keeps it open until you close
 * the [AppendFile].
 *
 * ```kotlin
 * (dirs.state / "app.log").openAppend().onSuccess { log ->
 *     log.use { it.append("started\n".encodeToByteArray()) }
 * }
 * ```
 *
 * Each [AppendFile.append] lands at the file's end in one write, so lines appended by two processes to one local
 * file never tear each other. A symlink is followed and the file it points at is opened.
 *
 * @return the open file; [FileError.NotRegularFile] when a directory, FIFO, device or socket is at the path,
 *   [FileError.Inaccessible] when the path may not be looked up, the error creating the parent folders gave, or
 *   [FileError.WriteFailed] with the platform's reason when the file would not open.
 */
public fun Path.openAppend(): Result<AppendFile, FileError> {
    val created = parent?.createDirectories()
    if (created is Result.Error) return created
    when (val found = metadata()) {
        is Result.Success -> if (!found.value.isRegularFile) {
            return Err(FileError.NotRegularFile(this, found.value.type))
        }
        is Result.Error -> if (found.error !is FileError.NotFound) return found
    }
    return openAppendFile(this)
}

/**
 * A file [openAppend] opened, which every [append] adds to at its end.
 *
 * Nothing here throws. Use it from one thread at a time, and close it once no call is running: an [append] after
 * [close] fails with [FileError.WriteFailed]. Closing it again does nothing.
 */
public interface AppendFile : AutoCloseable {
    /** The path the file was opened at, which may name another file or nothing by now: see [state]. */
    public val path: Path

    /**
     * Appends [bytes] at the end of the file in one write, so another process appending to the same local file
     * lands before or after them, never inside. A network file system does not promise that.
     *
     * @return [FileError.WriteFailed] with the platform's reason, such as a full disk. Part of [bytes] may have
     *   been written then.
     */
    public fun append(bytes: ByteArray): EmptyResult<FileError>

    /** Whether [path] still names this open file, as [AppendState] describes. */
    public fun state(): AppendState

    /**
     * The last byte of the open file, or null when it is empty; only that byte is read, however long the file. Where
     * the JVM has no /proc, it reads the file at [path] instead.
     *
     * @return [FileError.Inaccessible] when it cannot be read, such as a file this process may only write.
     */
    public fun lastByte(): Result<Byte?, FileError>
}

/** What became of the file an [AppendFile] has open, as seen from its [AppendFile.path]. */
public enum class AppendState {
    /** The path still names the open file. */
    Same,

    /** The open file still has a name, but the path names another file or nothing: it was moved or rotated. */
    Moved,

    /** The open file has no name left: it was deleted, and what is appended to it is lost once it is closed. */
    Deleted,

    /**
     * The path no longer names the open file, and this platform cannot tell whether it was moved or deleted: the
     * JVM on macOS and Windows. Also the answer when the path cannot be looked up.
     */
    Unknown,
}

/** Opens [path], which is a regular file or nothing, for appending. */
internal expect fun openAppendFile(path: Path): Result<AppendFile, FileError>
