package com.fromwau.kern.fs

import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileAttribute

// java.nio rather than the FileInputStream and FileOutputStream kotlinx-io opens, whose refusal is a
// FileNotFoundException of any cause.
internal actual fun openFileSource(
    path: Path,
    followLinks: Boolean,
    streams: Boolean,
): FileSource {
    refuseNotRegular(path, followLinks, streams)
    // Java cannot look up an open stream, so its info comes from the path, whose key is read on both sides.
    val keyBeforeOpen = try {
        keyAt(path)
    } catch (_: Exception) {
        null
    }
    val options = linkOptions(followLinks)
    return PathSource(path, keyBeforeOpen, Files.newInputStream(Paths.get(path.toString()), *options).asSource())
}

/**
 * Throws [NotRegularFileFailure] for anything at [path] but a regular file, a link the open refuses itself, or with
 * [streams] a FIFO or a character device. Java has no open that refuses a FIFO without waiting for the other end, so
 * the type is looked up first.
 */
private fun refuseNotRegular(
    path: Path,
    followLinks: Boolean,
    streams: Boolean = false,
) {
    val type = typeOrNull(path, followLinks) ?: return
    if (type == FileType.Symlink || type.isReadable(streams)) return
    throw NotRegularFileFailure(type)
}

/** What is at [path], a link followed when [followLinks]; null when it cannot be looked up, which the open answers. */
private fun typeOrNull(
    path: Path,
    followLinks: Boolean,
): FileType? = try {
    if (followLinks) fileInfoAt(path).type else ownFileTypeAt(path)
} catch (_: Exception) {
    null
}

/** The option that keeps an open from following a link at the last name, when it may not. */
private fun linkOptions(followLinks: Boolean): Array<LinkOption> =
    if (followLinks) emptyArray() else arrayOf(LinkOption.NOFOLLOW_LINKS)

private class PathSource(
    private val path: Path,
    private val keyBeforeOpen: Any?,
    source: RawSource,
) : FileSource,
    RawSource by source {
    override fun info(): FileInfo {
        val info = fileInfoAt(path)
        if (keyAt(path) != keyBeforeOpen) throw IOException("$path no longer names the file that was opened")
        return info
    }
}

internal actual fun openFileSink(
    path: Path,
    append: Boolean,
    permissions: Int?,
    followLinks: Boolean,
    newMode: Int,
): FileSink {
    refuseNotRegular(path, followLinks)
    val options = setOfNotNull(
        StandardOpenOption.CREATE,
        StandardOpenOption.WRITE,
        StandardOpenOption.APPEND.takeIf { append },
    ) + linkOptions(followLinks)
    val channel = Files.newByteChannel(Paths.get(path.toString()), options, *creationAttributes(permissions ?: newMode))
    try {
        // Emptied only once its mode is set, so a mode that cannot be set leaves the content as it was.
        if (permissions != null) setFilePermissions(path, permissions)
        if (!append) channel.truncate(0)
    } catch (e: Exception) {
        channel.close()
        throw e
    }
    return PathSink(path, Channels.newOutputStream(channel).asSink())
}

private class PathSink(
    private val path: Path,
    sink: RawSink,
) : FileSink,
    RawSink by sink {
    @OptIn(InternalKernApi::class)
    override fun setModifiedTime(time: Instant) = setLastModified(path, time)

    // A channel has no call of its own for these, so they go by the path, which this sink still holds.
    override fun permissionBits(): Int? = permissionBits(path)

    override fun setPermissions(bits: Int) = setFilePermissions(path, bits)

    override fun info(): FileInfo = fileInfoAt(path)
}

internal actual fun createHeldFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): HeldFile {
    val options = setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.READ, StandardOpenOption.WRITE)
    val creation = creationAttributes(permissions ?: newMode)
    val file = ChannelFile(path, FileChannel.open(Paths.get(path.toString()), options, *creation))
    try {
        // Set as well as asked for at the open, since the umask can narrow what the open asks for.
        if (permissions != null) file.setPermissions(permissions)
    } catch (e: Exception) {
        file.close()
        throw e
    }
    return file
}

// Applied only to a file the open creates.
internal fun creationAttributes(permissions: Int?): Array<FileAttribute<*>> = if (permissions != null && hasPosixView) {
    arrayOf(createdWithMode(permissions))
} else {
    emptyArray()
}

private class ChannelFile(
    private var path: Path,
    private val channel: FileChannel,
) : HeldFile {
    override fun moveTo(target: Path) {
        SystemFileSystem.atomicMove(path, target)
        path = target
    }

    override fun setPermissions(bits: Int) = setFilePermissions(path, bits)

    override fun sink(): RawSink = object : OutputStream() {
        override fun write(byte: Int) = write(byteArrayOf(byte.toByte()), 0, 1)

        override fun write(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ) {
            val buffer = ByteBuffer.wrap(bytes, offset, length)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }.asSink()

    override fun source(): RawSource = object : InputStream() {
        private var position = 0L

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(
            bytes: ByteArray,
            offset: Int,
            length: Int,
        ): Int {
            if (length == 0) return 0
            val count = channel.read(ByteBuffer.wrap(bytes, offset, length), position)
            if (count > 0) position += count
            return count
        }
    }.asSource()

    // A channel has no call of its own for this, so it goes by the path, which this file still holds.
    @OptIn(InternalKernApi::class)
    override fun setModifiedTime(time: Instant) = setLastModified(path, time)

    override fun sync() = channel.force(true)

    // Read by the held file's own name, the temp it was created as or the name it was moved to.
    override fun info(): FileInfo = fileInfoAt(path)

    override fun close() = channel.close()
}
