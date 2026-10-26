@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import kotlin.time.Instant
import platform.posix.EINTR
import platform.posix.ENXIO
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_APPEND
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NOFOLLOW
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.pread
import platform.posix.read
import platform.posix.stat
import platform.posix.write

private const val READ_CHUNK_BYTES = 8192L

internal actual fun openFileSource(
    path: Path,
    followLinks: Boolean,
    streams: Boolean,
): FileSource = openSourceAt(Opening(path, followLinks = followLinks), streams)

internal actual fun openFileSink(
    path: Path,
    append: Boolean,
    permissions: Int?,
    followLinks: Boolean,
    newMode: Int,
): FileSink = openSinkAt(Opening(path, followLinks = followLinks), append, permissions, newMode)

internal actual fun createHeldFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): HeldFile = createHeldFileAt(Opening(path), permissions, newMode)

/**
 * The file one open is about: [name] in the folder open as [folder], or the whole [path] from the working directory
 * when [folder] is [WORKING_DIRECTORY]. [path] names the file in what a failure says. With [followLinks] false, a
 * symlink at [name] is refused rather than followed.
 */
internal class Opening(
    val path: Path,
    val folder: Int = WORKING_DIRECTORY,
    val name: String = path.toString(),
    val followLinks: Boolean = true,
) {
    /** The flag that keeps the open from following a link at [name], when it may not. */
    val noFollow: Int get() = if (followLinks) 0 else O_NOFOLLOW
}

/** The `st_mode` of the file [Opening] names, a link followed only when the open would follow it; null unknown. */
private fun Opening.modeThere(): Int? = memScoped {
    val info = alloc<stat>()
    val found = if (followLinks) stat(path.toString(), info.ptr) else statusAt(folder, name, info.ptr)
    if (found == 0) info.st_mode.toInt() else null
}

/** What is at the file [Opening] names, a link followed only when the open would follow it. */
private fun Opening.typeThere(): FileType = modeThere()?.let(::fileTypeOfMode) ?: FileType.Other

/** [openFileSource] of what [opening] names. */
internal fun openSourceAt(
    opening: Opening,
    streams: Boolean = false,
): FileSource {
    val path = opening.path
    val descriptor = openRegularFile(opening, O_RDONLY, streams = streams)
    return DescriptorSource(
        path,
        descriptor,
        readInto = { buffer, size -> read(descriptor, buffer, size.convert()).convert() },
        onClose = { closeDescriptor(descriptor, path) },
    )
}

/** [openFileSink] of what [opening] names. */
internal fun openSinkAt(
    opening: Opening,
    append: Boolean,
    permissions: Int?,
    newMode: Int,
): FileSink {
    val path = opening.path
    val descriptor = openForWriting(opening, append, permissions, newMode)
    // Emptied only once its mode is set, so a mode that cannot be set leaves the content as it was.
    val failure = when {
        permissions != null && setDescriptorPermissionBits(descriptor, permissions) != 0 -> errnoFailure(about = path)
        !append && ftruncate(descriptor, 0) != 0 -> errnoFailure(about = path)
        else -> null
    }
    if (failure != null) {
        close(descriptor)
        throw failure
    }
    return DescriptorSink(path, descriptor, onClose = { closeDescriptor(descriptor, path) })
}

/** [createHeldFile] of what [opening] names, which moves within the folder it was made in. */
internal fun createHeldFileAt(
    opening: Opening,
    permissions: Int?,
    newMode: Int,
): HeldFile {
    val file = DescriptorFile(opening.path, createRegularFile(opening, permissions, newMode), opening.folder)
    try {
        // Set as well as asked for at the open, since the umask can narrow what the open asks for.
        if (permissions != null) file.setPermissions(permissions)
    } catch (e: Exception) {
        file.close()
        throw e
    }
    return file
}

/**
 * [openFileSink]'s open of [opening], creating a missing file with [permissions] when given so it is never wider, and
 * with [newMode] otherwise.
 */
internal fun openForWriting(
    opening: Opening,
    append: Boolean,
    permissions: Int?,
    newMode: Int = NEW_FILE_MODE,
): Int = openRegularFile(opening, O_WRONLY or O_CREAT or if (append) O_APPEND else 0, permissions ?: newMode)

/** [createHeldFile]'s open, creating [opening] with [permissions] when given so it is never wider, else [newMode]. */
internal fun createRegularFile(
    opening: Opening,
    permissions: Int?,
    newMode: Int = NEW_FILE_MODE,
): Int = openRegularFile(opening, O_RDWR or O_CREAT or O_EXCL, permissions ?: newMode)

/** [openRegularFile] of [path] from the working directory. */
internal fun openRegularFile(
    path: Path,
    flags: Int,
    creationMode: Int = NEW_FILE_MODE,
): Int = openRegularFile(Opening(path), flags, creationMode)

/**
 * Opens what [opening] names with [flags], creating a file with [creationMode] where they say so, and refuses anything
 * there but a regular file with [NotRegularFileFailure], or with [streams] anything but that, a FIFO or a character
 * device. Throws why it could not open.
 *
 * Without [streams] the open does not wait: a FIFO that appeared after the caller looked would otherwise block it until
 * something opened the other end. Waiting is switched back on once the file is known to be regular, which ignores it
 * anyway. With [streams] the open waits, since a FIFO opened without waiting reads as ended until a writer comes.
 */
internal fun openRegularFile(
    opening: Opening,
    flags: Int,
    creationMode: Int = NEW_FILE_MODE,
    streams: Boolean = false,
): Int {
    val path = opening.path
    if (streams) {
        // Looked at first, so a waiting open never reaches a socket or a block device.
        val type = opening.modeThere()?.let(::fileTypeOfMode)
        if (type != null && !type.isReadable(streams)) throw NotRegularFileFailure(type)
    }
    val allFlags = flags or opening.noFollow or O_CLOEXEC or if (streams) 0 else O_NONBLOCK
    val descriptor = openAt(opening.folder, opening.name, allFlags, creationMode)
    if (descriptor < 0) {
        val code = errno
        val failure = errnoFailure(about = path)
        // A write-only open that does not wait answers this for a FIFO no one reads, and any open for a device with
        // nothing behind it, such as the terminal of a process that has none.
        if (code == ENXIO) {
            val type = opening.typeThere()
            if (!type.isReadable(streams)) throw NotRegularFileFailure(type)
        }
        throw failure
    }
    val failure = memScoped {
        val info = alloc<stat>()
        if (fstat(descriptor, info.ptr) != 0) return@memScoped errnoFailure(about = path)
        val type = fileTypeOfMode(info.st_mode.convert())
        if (!type.isReadable(streams)) return@memScoped NotRegularFileFailure(type)
        val status = fcntl(descriptor, F_GETFL)
        val waits = status >= 0 && fcntl(descriptor, F_SETFL, status and O_NONBLOCK.inv()) >= 0
        if (waits) null else errnoFailure(about = path)
    }
    if (failure != null) {
        close(descriptor)
        throw failure
    }
    return descriptor
}

/** Writes all of [bytes] to [descriptor], open on [path], answering why it could not. */
internal fun writeFully(
    descriptor: Int,
    bytes: ByteArray,
    path: Path,
): Exception? = writeAll(bytes, path) { buffer, size -> write(descriptor, buffer, size.convert()).convert() }

/**
 * Writes all of [bytes] through [writeOnce], which sends up to the asked-for bytes from the buffer where they go and
 * answers how many, or -1 on failure, answering why it could not; [path] names the file in that failure. A regular
 * file takes the whole write at once; the loop only covers an interrupted or short one.
 */
internal fun writeAll(
    bytes: ByteArray,
    path: Path,
    writeOnce: (buffer: CPointer<ByteVar>, size: Int) -> Long,
): Exception? {
    if (bytes.isEmpty()) return null
    bytes.usePinned { pinned ->
        var written = 0
        while (written < bytes.size) {
            val result = writeOnce(pinned.addressOf(written), bytes.size - written)
            when {
                result > 0 -> written += result.toInt()
                // Asked again, it would take nothing again, forever.
                result == 0L -> {
                    return OsCallFailure(IoCause.Other, path, "wrote none of the ${bytes.size - written} bytes left")
                }
                errno != EINTR -> return errnoFailure(about = path)
            }
        }
    }
    return null
}

/**
 * `fchmod` on [descriptor], answering 0 or -1 as it does. Declared per target for the reason [setPermissionBits] is.
 */
internal expect fun setDescriptorPermissionBits(
    descriptor: Int,
    bits: Int,
): Int

/**
 * A file held open as [descriptor], made at [path], which [moveTo] renames in the folder open as [folder], or from the
 * working directory when that is [WORKING_DIRECTORY].
 */
private class DescriptorFile(
    private var path: Path,
    private val descriptor: Int,
    private val folder: Int,
) : HeldFile {
    private var closed = false

    override fun moveTo(target: Path) {
        val from = if (folder == WORKING_DIRECTORY) path.toString() else path.name
        val to = if (folder == WORKING_DIRECTORY) target.toString() else target.name
        if (renameAt(folder, from, folder, to) != 0) throw errnoFailure(about = path)
        path = target
    }

    override fun setPermissions(bits: Int) {
        if (setDescriptorPermissionBits(descriptor, bits) != 0) throw errnoFailure(about = path)
    }

    override fun sink(): RawSink = DescriptorSink(path, descriptor, onClose = {})

    override fun source(): RawSource {
        var offset = 0L
        val readInto = { buffer: CPointer<ByteVar>, size: Int ->
            val count: Long = pread(descriptor, buffer, size.convert(), offset.convert()).convert()
            if (count > 0) offset += count
            count
        }
        return DescriptorSource(path, descriptor, readInto, onClose = {})
    }

    override fun setModifiedTime(time: Instant) {
        if (setDescriptorModifiedTime(descriptor, time) != 0) throw errnoFailure(about = path)
    }

    override fun sync() {
        if (fsync(descriptor) != 0) throw errnoFailure(about = path)
    }

    override fun info(): FileInfo = fileInfoOf(descriptor, path)

    override fun close() {
        if (closed) return
        closed = true
        if (close(descriptor) != 0) throw errnoFailure(about = path)
    }
}

private fun closeDescriptor(
    descriptor: Int,
    path: Path,
) {
    if (close(descriptor) != 0) throw errnoFailure(about = path)
}

/**
 * A source over what [readInto] reads of [path], open as [descriptor]: up to the asked-for bytes into the buffer, 0 at
 * the end, -1 on failure. [onClose] runs on the first close.
 */
private class DescriptorSource(
    private val path: Path,
    private val descriptor: Int,
    private val readInto: (buffer: CPointer<ByteVar>, size: Int) -> Long,
    private val onClose: () -> Unit,
) : FileSource {
    private var closed = false

    override fun info(): FileInfo = fileInfoOf(descriptor, path)

    override fun readAtMostTo(
        sink: Buffer,
        byteCount: Long,
    ): Long {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "closed" }
        if (byteCount == 0L) return 0L
        val chunk = ByteArray(minOf(byteCount, READ_CHUNK_BYTES).toInt())
        val count = chunk.usePinned { pinned ->
            var result: Long
            do {
                result = readInto(pinned.addressOf(0), chunk.size)
                if (result < 0 && errno != EINTR) throw errnoFailure(about = path)
            } while (result < 0)
            result
        }
        if (count == 0L) return -1L
        sink.write(chunk, 0, count.toInt())
        return count
    }

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}

/** A sink writing straight to [descriptor], open on [path]; [onClose] runs on the first close. */
private class DescriptorSink(
    private val path: Path,
    private val descriptor: Int,
    private val onClose: () -> Unit,
) : FileSink {
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "closed" }
        writeFully(descriptor, source.readByteArray(byteCount.toInt()), path)?.let { throw it }
    }

    override fun setModifiedTime(time: Instant) {
        if (setDescriptorModifiedTime(descriptor, time) != 0) throw errnoFailure(about = path)
    }

    override fun permissionBits(): Int = fileInfoOf(descriptor, path).permissions?.bits ?: 0

    override fun setPermissions(bits: Int) {
        if (setDescriptorPermissionBits(descriptor, bits) != 0) throw errnoFailure(about = path)
    }

    override fun info(): FileInfo = fileInfoOf(descriptor, path)

    // Every write goes straight to the descriptor, so nothing is held back to flush.
    override fun flush() = Unit

    override fun close() {
        if (closed) return
        closed = true
        onClose()
    }
}
