package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.convert
import kotlinx.io.Buffer
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import platform.windows.CREATE_NEW
import platform.windows.CloseHandle
import platform.windows.FILE_APPEND_DATA
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_TYPE_DISK
import platform.windows.FILE_WRITE_ATTRIBUTES
import platform.windows.GENERIC_WRITE
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.OPEN_ALWAYS
import platform.windows.SetEndOfFile
import kotlin.time.Instant

// kotlinx-io's own opens, whose failures carry only a message, so on Windows native they are always IoCause.Other.
// They have no way to refuse a link, so followLinks is not kept here. Unverified on Windows.
internal actual fun openFileSource(
    path: Path,
    followLinks: Boolean,
    streams: Boolean,
): FileSource {
    refuseNotRegular(path, streams)
    return PathSource(path, SystemFileSystem.source(path))
}

/**
 * Throws [NotRegularFileFailure] for anything at [path] but a regular file or, with [streams], a character device or
 * pipe. kotlinx-io's open takes anything, so the type is looked up first; one that cannot be looked up is left to the
 * open to answer.
 */
private fun refuseNotRegular(
    path: Path,
    streams: Boolean,
) {
    val type = try {
        fileInfoAt(path).type
    } catch (_: Exception) {
        return
    }
    if (type.isReadable(streams)) return
    throw NotRegularFileFailure(type)
}

// kotlinx-io hands out no handle to look up, so the info comes from the path.
private class PathSource(
    private val path: Path,
    source: RawSource,
) : FileSource,
    RawSource by source {
    override fun info(): FileInfo = fileInfoAt(path)
}

// Of the permissions, a Windows file carries only whether it is read-only, which would refuse the content, so they
// are set once the sink is closed. A link is followed whatever followLinks says. Unverified on Windows.
@OptIn(ExperimentalForeignApi::class)
internal actual fun openFileSink(
    path: Path,
    append: Boolean,
    permissions: Int?,
    followLinks: Boolean,
    newMode: Int,
): FileSink {
    // FILE_APPEND_DATA without FILE_WRITE_DATA puts every write at the end, as Appending does.
    val writing = if (append) FILE_APPEND_DATA.convert<UInt>() else GENERIC_WRITE.convert<UInt>()
    val access = writing or FILE_READ_ATTRIBUTES.convert<UInt>() or FILE_WRITE_ATTRIBUTES.convert<UInt>()
    val handle = openHandle(path, access, OPEN_ALWAYS) ?: throw windowsFailure(GetLastError(), path)
    val refusal = when {
        GetFileType(handle) != FILE_TYPE_DISK.convert<UInt>() ->
            NotRegularFileFailure(streamTypeOf(handle) ?: FileType.Other)
        !append && SetEndOfFile(handle) == 0 -> windowsFailure(GetLastError(), path)
        else -> null
    }
    if (refusal != null) {
        CloseHandle(handle)
        throw refusal
    }
    return HandleSink(path, handle, permissions)
}

@OptIn(ExperimentalForeignApi::class)
private class HandleSink(
    private val path: Path,
    private val handle: HANDLE,
    private val permissions: Int?,
) : FileSink {
    private var closed = false

    override fun write(
        source: Buffer,
        byteCount: Long,
    ) {
        require(byteCount >= 0L) { "byteCount: $byteCount" }
        check(!closed) { "closed" }
        writeFully(handle, source.readByteArray(byteCount.toInt()), path)?.let { throw it }
    }

    @OptIn(InternalKernApi::class)
    override fun setModifiedTime(time: Instant) = setLastModified(path, time)

    override fun permissionBits(): Int? = permissionBits(path)

    override fun setPermissions(bits: Int) = setFilePermissions(path, bits)

    override fun info(): FileInfo = handleInfo(handle, path)

    // Every write goes straight to the handle, so nothing is held back to flush.
    override fun flush() = Unit

    override fun close() {
        if (closed) return
        closed = true
        CloseHandle(handle)
        permissions?.let { setFilePermissions(path, it) }
    }
}

// A Windows file has no POSIX mode to be created with, and each call but info opens it again, which a read-only one
// refuses.
@OptIn(ExperimentalForeignApi::class)
internal actual fun createHeldFile(
    path: Path,
    permissions: Int?,
    newMode: Int,
): HeldFile {
    // Held for its attributes alone and shared every way, so the opens, renames and removals by path all still work.
    val handle = openHandle(path, FILE_READ_ATTRIBUTES.convert(), CREATE_NEW)
        ?: throw windowsFailure(GetLastError(), path)
    return PathFile(path, handle)
}

// kotlinx-io has no file open for both reading and writing, so each call but info opens the file again by its path.
@OptIn(ExperimentalForeignApi::class)
private class PathFile(
    private var path: Path,
    private val handle: HANDLE,
) : HeldFile {
    private var closed = false

    override fun moveTo(target: Path) {
        SystemFileSystem.atomicMove(path, target)
        path = target
    }

    override fun setPermissions(bits: Int) = setFilePermissions(path, bits)

    override fun sink(): RawSink = openFileSink(path, append = true)

    override fun source(): RawSource = openFileSource(path)

    @OptIn(InternalKernApi::class)
    override fun setModifiedTime(time: Instant) = setLastModified(path, time)

    override fun sync() {
        syncFile(path)?.let { throw it }
    }

    override fun info(): FileInfo = handleInfo(handle, path)

    override fun close() {
        if (closed) return
        closed = true
        CloseHandle(handle)
    }
}
