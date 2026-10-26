package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import kotlin.time.Instant
import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray

/** Whether anything is at this path, following symlinks. Also `false` when the path cannot be looked up. */
public fun Path.exists(): Boolean = refusedPath() == null && SystemFileSystem.exists(this)

/**
 * The whole file as UTF-8 text, read the way [readBytes] reads it; bytes that are not valid UTF-8 read as
 * U+FFFD. Use [readBytes] to decode the file some other way, or to refuse one that is not UTF-8.
 *
 * @param maxBytes the largest file read, in bytes; a larger one is [FileError.TooLarge]. Null reads the whole
 *   file, however large.
 * @param trusted refuses a file someone besides you may change, or one in a folder they may change, as [readBytes]
 *   refuses it.
 * @param streams reads a FIFO or a character device too, to its end, as [readBytes] reads it.
 * @param accept asked about the file once it is open, before any of it is read, as [readBytes] asks it.
 */
public fun Path.readText(
    maxBytes: Long? = null,
    trusted: Boolean = false,
    streams: Boolean = false,
    accept: ((FileInfo) -> Boolean)? = null,
): Result<String, FileError> = readBytes(maxBytes, trusted, streams, accept).map { it.decodeToString() }

/**
 * The whole file as bytes. A directory, FIFO, device or socket is [FileError.NotRegularFile] and is never
 * opened, since opening a FIFO blocks until something writes to it. [streams] lets a FIFO or a character device in.
 *
 * ```kotlin
 * val bytes = file
 *     .readBytes(maxBytes = 1_048_576, accept = { it.ownedByMe != false })
 *     .getOrElse { return warn(it) }
 * ```
 *
 * @param maxBytes the largest file read, in bytes; a larger one is [FileError.TooLarge]. Null reads the whole
 *   file, however large.
 * @param trusted refuses a file someone besides you may change: another user owns it, or its group or everyone else may
 *   write it. That is judged on the file this call opened, as [accept] is. It refuses the same way the folder the path
 *   sits in and, for a link, the folder the file it leads to really sits in, since whoever may write a folder can swap
 *   the file for their own. Either is [FileError.Refused] naming the file or folder, with this path as its `asked` and
 *   [FileError.Refused.why] saying which, and nothing is read. Windows, which says neither, refuses nothing.
 * @param streams true also reads a FIFO or a character device, such as `/dev/stdin` or `/dev/null`, to its end.
 *   Opening a FIFO waits until something opens it for writing, and the read lasts until every writer has closed it,
 *   so this call may block for as long as that takes. A stream has no size up front, so [maxBytes] is checked as it
 *   is read: once more than that arrives, it is [FileError.TooLarge]. Give it for an endless device such as
 *   `/dev/zero`. A folder, a socket and a block device stay [FileError.NotRegularFile], since a block device holds a
 *   whole disk rather than a stream. [trusted] judges the folder before the open and the FIFO or device itself once
 *   it is open, so after its writer came. Windows native reads a device such as `NUL` or `COM1` and a named pipe
 *   such as `\\.\pipe\name`, unverified on Windows: a device or pipe may be [FileError.NotFound] before it is
 *   typed, and reading a named pipe likely fails, since each lookup connects to it as a client. The JVM on Windows
 *   reads no stream, so there it changes nothing.
 * @param accept asked about the file this call opened, as [info] would describe it, before any of it is read. False
 *   is [FileError.Refused] with that info, and nothing is read. On Linux and Apple the info is the open file's own, so
 *   a file swapped in at the path after the open is never the one described. The JVM and Android look the path up
 *   after the open, and a file whose file key is not that of the file the path named when it was opened is
 *   [FileError.Inaccessible]. The JVM on Windows and Windows native, which have no file keys, look the path up after
 *   the open. A file [trusted] refused is never passed to it. What it throws is thrown from this call, with the file
 *   closed.
 */
public fun Path.readBytes(
    maxBytes: Long? = null,
    trusted: Boolean = false,
    streams: Boolean = false,
    accept: ((FileInfo) -> Boolean)? = null,
): Result<ByteArray, FileError> = readableMetadata(streams).flatMap { metadata ->
    when {
        // A stream's size is unknown, below zero, until it is read.
        maxBytes != null && metadata.size >= 0 && metadata.size > maxBytes -> {
            Err(FileError.TooLarge(this, maxBytes, metadata.size))
        }
        trusted -> untrustedFolder()?.let { Err(it) } ?: readWhole(maxBytes, accept, trusted = true, streams)
        else -> readWhole(maxBytes, accept, streams = streams)
    }
}

/**
 * [FileError.Refused] for the folder this file sits in, or for the one the file a link here leads to really sits in,
 * when someone besides this user may change it, since whoever may write a folder can swap the file for their own.
 */
private fun Path.untrustedFolder(): FileError? {
    val real = canonicalPath().getOrElse { return it }
    for (folder in listOfNotNull(folderOrDot(), real.parent).distinct()) {
        val info = folder.info().getOrElse { return it }
        info.untrustedAt(folder, asked = this)?.let { return it }
    }
    return null
}

/**
 * Up to [maxBytes] from the start of the file, all of it when it is shorter, so a file larger than that is never
 * [FileError.TooLarge]. What is no regular file is refused as [readBytes] refuses it, without being opened.
 *
 * ```kotlin
 * val head = image.readPrefix(maxBytes = 8).getOrElse { return warn(it) }
 * if (!head.contentEquals(PNG_SIGNATURE)) println("$image is no PNG")
 * ```
 *
 * @param maxBytes how many bytes to read at most; zero or less reads none.
 */
public fun Path.readPrefix(maxBytes: Long): Result<ByteArray, FileError> = readableMetadata().flatMap {
    openSource().flatMap { readFrom(it, Read.Prefix(maxBytes.coerceAtLeast(0))) }
}

/**
 * Opens this file, or with [streams] the FIFO or character device, and reads it whole, or [FileError.TooLarge] past
 * [maxBytes], once [accept] took the opened file's info. [onOpened] runs right after the open.
 */
internal fun Path.readWhole(
    maxBytes: Long?,
    accept: ((FileInfo) -> Boolean)? = null,
    trusted: Boolean = false,
    streams: Boolean = false,
    onOpened: () -> Unit = {},
): Result<ByteArray, FileError> = openSource(streams).flatMap { opened ->
    onOpened()
    val refusal = if (accept == null && !trusted) {
        null
    } else {
        try {
            refusalOf(opened, trusted, accept)
        } catch (e: Throwable) {
            opened.closeQuietly()
            throw e
        }
    }
    if (refusal == null) {
        readFrom(opened, Read.Whole(maxBytes))
    } else {
        opened.closeQuietly()
        Err(refusal)
    }
}

/**
 * [FileError.Refused] when the file [opened] holds is untrusted while [trusted] asks for a trusted one, or one others
 * may read when [private] asks for a private one, or when [accept] refuses it after that, naming that file as [file]
 * and this path as asked; why its info could not be had; or null.
 */
internal fun Path.refusalOf(
    opened: FileSource,
    trusted: Boolean,
    accept: ((FileInfo) -> Boolean)?,
    private: Boolean = false,
    file: Path = canonicalPath().getOrNull() ?: this,
): FileError? {
    val info = try {
        opened.info()
    } catch (e: Exception) {
        return inaccessible(this, e)
    }
    val judged = when {
        private -> info.unprivateAt(file, asked = this)
        trusted -> info.untrustedAt(file, asked = this)
        else -> null
    }
    if (judged != null) return judged
    if (accept == null || accept(info)) return null
    return FileError.Refused(file, info, RefusedBecause.Accept, this)
}

/** How much of a file [readFrom] reads. */
internal sealed interface Read {
    /** All of it, or [FileError.TooLarge] once it holds more than [maxBytes] when that is set. */
    data class Whole(val maxBytes: Long?) : Read

    /** Its first [bytes], or all of it when shorter. */
    data class Prefix(val bytes: Long) : Read
}

/** Reads [opened], this file, as [read] says, and closes it. */
internal fun Path.readFrom(
    opened: RawSource,
    read: Read,
): Result<ByteArray, FileError> = try {
    opened
        .buffered()
        .use { source ->
            when (read) {
                is Read.Prefix -> {
                    val prefix = Buffer()
                    var more = true
                    while (more && prefix.size < read.bytes) {
                        more = source.readAtMostTo(prefix, read.bytes - prefix.size) >= 0
                    }
                    Ok(prefix.readByteArray())
                }
                is Read.Whole -> {
                    val limit = read.maxBytes
                    // The file can grow or be replaced after the size check, so the limit bounds the read as well.
                    // Below zero no byte fits, so even an empty stream is too large, as an empty file is.
                    val tooMany = if (limit == null || limit == Long.MAX_VALUE) null else maxOf(limit + 1, 0)
                    if (limit != null && tooMany != null && source.request(tooMany)) {
                        Err(FileError.TooLarge(this, limit, tooMany))
                    } else {
                        Ok(source.readByteArray())
                    }
                }
            }
        }
} catch (e: Exception) {
    Err(inaccessible(this, e))
}

/**
 * What is at this path, read in one lookup.
 *
 * ```kotlin
 * if (entry.fileType(followLinks = false) == Ok(FileType.Symlink)) {
 *     entry.delete().onError { println("link not removed: $it") }   // the link, never what it leads to
 * }
 * ```
 *
 * @param followLinks true answers what a symlink at this path finally leads to, so never [FileType.Symlink]. False
 *   answers [FileType.Symlink] for any link, one whose target is missing or that goes round included, and what is
 *   there for anything else; links among the folders above are followed either way. Windows native, unverified, names
 *   every reparse point a link, a junction included.
 * @return the type; [FileError.NotFound] when nothing is there, or [FileError.Inaccessible] when it may not be looked
 *   up, a loop of links included when they are followed.
 */
public fun Path.fileType(followLinks: Boolean = true): Result<FileType, FileError> {
    if (followLinks) return metadata().map { typeOf(it) }
    refusedPath()?.let { return it }
    return try {
        ownFileTypeAt(this)?.let { Ok(it) } ?: Err(FileError.NotFound(this))
    } catch (e: Exception) {
        Err(inaccessible(this, e))
    }
}

/**
 * The size in bytes of the file at this path, following symlinks. Anything but a regular file is
 * [FileError.NotRegularFile].
 */
public fun Path.fileSize(): Result<Long, FileError> = readableMetadata().map { it.size }

/**
 * [metadata] for a regular file at this path, or with [streams] a FIFO or character device; anything else is
 * [FileError.NotRegularFile] naming what it is.
 */
private fun Path.readableMetadata(streams: Boolean = false): Result<FileMetadata, FileError> =
    metadata().flatMap { metadata ->
        val type = typeOf(metadata)
        if (type.isReadable(streams)) Ok(metadata) else Err(FileError.NotRegularFile(this, type))
    }

/**
 * When the file, folder or other entry at this path was last written, following symlinks, to the precision the
 * file system keeps.
 */
public fun Path.modifiedAt(): Result<Instant, FileError> = info().map { it.modifiedAt }

/**
 * This directory's entries, as full paths in name order. An entry is listed as it is, so a symlink appears as
 * itself and is not followed. A file, FIFO, device or socket at this path is [FileError.NotADirectory].
 */
public fun Path.list(): Result<List<Path>, FileError> = metadata().flatMap { metadata ->
    if (!metadata.isDirectory) {
        Err(FileError.NotADirectory(this, typeOf(metadata)))
    } else {
        try {
            val entries = SystemFileSystem.list(this).sortedBy { it.name }
            val denial = if (entries.isEmpty()) listDenial(this) else null
            if (denial == null) Ok(entries) else Err(inaccessible(this, denial.failure))
        } catch (e: Exception) {
            Err(inaccessible(this, listDenial(this)?.failure ?: e))
        }
    }
}

/**
 * Metadata for this path, following symlinks; [FileError.NotFound] only when nothing is really there. [lookUp] is
 * the first look at the path.
 */
internal fun Path.metadata(
    lookUp: (Path) -> FileMetadata? = ::systemMetadataOrNull,
): Result<FileMetadata, FileError> {
    refusedPath()?.let { return it }
    val found = lookUp(this)
    // kotlinx-io's JVM lookup reads a file deleted halfway through it as neither file nor folder, so only the probe,
    // which sees the path at one moment, says what such an answer is.
    if (found != null && (found.isRegularFile || found.isDirectory)) return Ok(found)

    return when (val probe = probePath(this)) {
        is PathProbe.Present -> Ok(probe.metadata)
        PathProbe.Absent -> Err(FileError.NotFound(this))
        is PathProbe.Denied -> Err(inaccessible(this, probe.failure))
    }
}

// kotlinx-io answers null both for a missing path and for one it may not look at, and on Linux and Windows throws
// for every errno but ENOENT. Neither says which, so anything but a straight answer goes to the probe.
private fun systemMetadataOrNull(path: Path): FileMetadata? = try {
    SystemFileSystem.metadataOrNull(path)
} catch (_: Exception) {
    null
}

/**
 * Why this path, there a moment ago, failed with [failure]: [FileError.NotFound] when the failure says so or it has
 * gone since, otherwise [FileError.Inaccessible]. A failure naming no cause takes looking it up again to tell apart.
 */
internal fun Path.inaccessibleUnlessGone(failure: Exception): Result<Nothing, FileError> {
    // Not looked up again: a file renamed into place since would turn this NotFound into Inaccessible.
    if (failure.ioCause() == IoCause.NotFound) return Err(FileError.NotFound(this))
    return metadata().flatMap { Err(inaccessible(this, failure)) }
}

/**
 * The error every fs call answers for this path before the system sees it, or null for a path it hands on: the empty
 * path is [FileError.NotFound], since each platform reads it its own way, some as the working directory; a path
 * holding a NUL is [FileError.InvalidName]; and one with a name in it longer than 255 bytes in UTF-8 is
 * [FileError.NameTooLong] with [LongName.Path].
 */
@InternalKernApi
public fun Path.pathError(): FileError? =
    if (toString().isEmpty()) FileError.NotFound(this) else refusedName()?.error

/** [pathError] as the error result every public call returns for it. */
@OptIn(InternalKernApi::class)
internal fun Path.refusedPath(): Err<FileError>? = pathError()?.let(::Err)

/**
 * [FileError.InvalidName] for a path holding a NUL, which a native call reads as the end of the path and so would
 * reach another file, or [FileError.NameTooLong] for one with a name longer than [LONGEST_NAME_BYTES]; null otherwise.
 * A symlink's target, which may be empty, is checked by this alone.
 */
internal fun Path.refusedName(): Err<FileError>? {
    if (NUL in toString()) return Err(FileError.InvalidName(this, NameProblem.Nul))
    val longest = generateSequence(this) { it.parent }.maxOf { it.name.encodeToByteArray().size }
    return if (longest <= LONGEST_NAME_BYTES) null else Err(FileError.NameTooLong(this, LongName.Path, longest))
}

internal const val NUL = '\u0000'

/** The longest file or folder name, in UTF-8 bytes, kern accepts; a longer one is [FileError.NameTooLong]. */
public const val LONGEST_NAME_BYTES: Int = 255
