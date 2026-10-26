package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.Path as JavaPath

private const val OPEN_ATTEMPTS = 4

internal actual fun openAppendFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): Result<AppendFile, FileError> = openAppendFile(path, permissions, newMode, onOpened = {})

// Java cannot ask an open stream for its file key, so the path's key is read on both sides of the open; [onOpened]
// runs between the open and the second read.
internal fun openAppendFile(
    path: Path,
    permissions: Int?,
    newMode: Int = NEW_FILE_MODE,
    onOpened: () -> Unit,
): Result<AppendFile, FileError> = try {
    openStream(path, permissions, newMode, onOpened)
} catch (e: Exception) {
    Err(writeFailed(path, e))
}

private fun openStream(
    path: Path,
    permissions: Int?,
    newMode: Int,
    onOpened: () -> Unit,
): Result<AppendFile, FileError> {
    // java.io refuses an open with a FileNotFoundException whatever the cause; java.nio's exception types say which.
    val options = setOf(StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)
    Files.newByteChannel(Paths.get(path.toString()), options, *creationAttributes(permissions ?: newMode)).close()
    repeat(OPEN_ATTEMPTS) {
        val before = keyAt(path)
        // FileOutputStream.write(ByteArray) hands the whole array to one system write, opened with O_APPEND, and on
        // Windows it opens without FILE_SHARE_DELETE, so the file cannot be moved or deleted while it is open.
        val out = FileOutputStream(path.toString(), true)
        val after = try {
            onOpened()
            keyAt(path)
        } catch (e: Exception) {
            out.close()
            throw e
        }
        // Windows has no file key, so there only a missing file is noticed.
        val kept = after == null || after != NoFile && after == before
        if (kept) {
            try {
                // A stream has no call to set its file's mode, so it goes by the path, which named the open file when
                // last checked.
                if (permissions != null) setFilePermissions(path, permissions)
            } catch (e: Exception) {
                out.close()
                throw e
            }
            return Ok(StreamAppendFile(path, out, opened = after))
        }
        // Created, moved or deleted around the open: the stream may hold a file the path no longer names.
        out.close()
    }
    return Err(writeFailed(path, IOException("replaced while it was being opened")))
}

private class StreamAppendFile(
    override val path: Path,
    private val out: FileOutputStream,
    private val opened: Any?,
) : AppendFile {
    @Volatile
    private var closed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (closed) return Err(FileError.Closed(path))
        return try {
            out.write(bytes)
            Ok(Unit)
        } catch (e: Exception) {
            Err(writeFailed(path, e))
        }
    }

    // The descriptor's link under /proc/self/fd, found once, since the number stays this stream's until it closes.
    private var descriptorLink: JavaPath? = null

    override fun state(): Result<AppendState, FileError> {
        if (closed) return Ok(AppendState.Closed)
        val now = try {
            keyAt(path)
        } catch (e: Exception) {
            // java.nio throws a plain FileSystemException for ENOTDIR, so kern's own lookup decides missing or refused.
            when (val looked = path.metadata()) {
                is Result.Error -> if (looked.error is FileError.NotFound) NoFile else return looked
                is Result.Success -> return Err(inaccessible(path, e))
            }
        }
        // Without file keys, as on Windows, the file is opened so that it cannot be deleted or moved while open.
        if (opened == null) return Ok(if (now == NoFile) AppendState.Unknown else AppendState.Same)
        val links = linkCount(opened)
        return Ok(
            when {
                links == 0 -> AppendState.Deleted
                now == opened -> AppendState.Same
                links == null -> AppendState.Unknown
                else -> AppendState.Moved
            },
        )
    }

    /** How many names the open file has, read through [descriptor]; null without `/proc`, as on macOS. */
    private fun linkCount(key: Any): Int? = try {
        descriptor(key)?.let { Files.getAttribute(it, "unix:nlink") as? Int }
    } catch (_: Exception) {
        null
    }

    /**
     * The open file's descriptor under `/proc/self/fd`, which reaches the file itself even once deleted, or null
     * without `/proc`. Java hides a stream's descriptor number, so it is found by its file key, which no other file
     * can have while this one is open.
     */
    private fun descriptor(key: Any): JavaPath? {
        val link = descriptorLink?.takeIf { keyOrNull(it) == key } ?: findDescriptor(key)
        descriptorLink = link
        return link
    }

    override fun lastByte(): Result<Byte?, FileError> {
        if (closed) return Err(FileError.Closed(path))
        // Without /proc the path is read instead, which names the open file wherever state() answers Same.
        val source = opened?.let { descriptor(it) }?.toString() ?: path.toString()
        return try {
            // Opened by java.nio first for a cause the type says, as openStream does.
            Files.newByteChannel(Paths.get(source)).close()
            RandomAccessFile(source, "r").use { file ->
                val length = file.length()
                if (length <= 0L) return Ok(null)
                file.seek(length - 1)
                Ok(file.read().takeIf { it >= 0 }?.toByte())
            }
        } catch (e: Exception) {
            Err(inaccessible(path, e))
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            out.close()
        } catch (_: Exception) {
            // Nothing is left to do with a stream that will not close.
        }
    }
}

private fun findDescriptor(key: Any): JavaPath? = try {
    Files.newDirectoryStream(Paths.get("/proc/self/fd")).use { links -> links.firstOrNull { keyOrNull(it) == key } }
} catch (_: Exception) {
    null
}

// A descriptor closed meanwhile, or one with nothing to read, such as a socket, has no key.
private fun keyOrNull(path: JavaPath): Any? = try {
    keyOf(path)
} catch (_: Exception) {
    null
}

/** Stands for a path that names nothing, where a null key means the platform has no file keys at all. */
internal object NoFile

/** The device and inode behind [path], [NoFile] when it names nothing, or null where the platform has no keys. */
internal fun keyAt(path: Path): Any? = try {
    keyOf(Paths.get(path.toString()))
} catch (_: NoSuchFileException) {
    NoFile
}

private fun keyOf(path: JavaPath): Any? = Files.readAttributes(path, BasicFileAttributes::class.java).fileKey()
