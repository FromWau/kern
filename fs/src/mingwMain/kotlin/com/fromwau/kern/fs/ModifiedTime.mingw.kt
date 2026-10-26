package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.io.files.Path
import platform.windows.BY_HANDLE_FILE_INFORMATION
import platform.windows.CloseHandle
import platform.windows.FILETIME
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_ATTRIBUTE_REPARSE_POINT
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_FLAG_OPEN_REPARSE_POINT
import platform.windows.FILE_READ_ATTRIBUTES
import platform.windows.FILE_TYPE_CHAR
import platform.windows.FILE_TYPE_PIPE
import platform.windows.FILE_WRITE_ATTRIBUTES
import platform.windows.GetFileAttributesExW
import platform.windows.GetFileInformationByHandle
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.OPEN_EXISTING
import platform.windows.SetFileTime
import platform.windows.WIN32_FILE_ATTRIBUTE_DATA
import platform.windows._GET_FILEEX_INFO_LEVELS
import kotlin.time.Instant

// FILETIME counts 100-nanosecond ticks since 1601-01-01, this many before the Unix epoch.
private const val TICKS_TO_UNIX_EPOCH = 116_444_736_000_000_000L
private const val TICKS_PER_SECOND = 10_000_000L
private const val NANOS_PER_TICK = 100L

// FILETIME zero, Windows' own "no time", for a device or pipe that keeps none.
private val NO_TIME = Instant.fromEpochSeconds(-TICKS_TO_UNIX_EPOCH / TICKS_PER_SECOND)

internal actual fun fileInfoAt(path: Path): FileInfo = attributeInfoAt(path, ownType = false)

// Unverified on Windows, as ownFileTypeAt is.
internal actual fun ownFileInfoAt(path: Path): FileInfo = attributeInfoAt(path, ownType = true)

/**
 * The info of what is at [path], read through a handle opened for its attributes alone, a reparse point there typed
 * [FileType.Symlink] and described itself with [ownType]. Unverified on Windows.
 */
@OptIn(ExperimentalForeignApi::class)
private fun attributeInfoAt(
    path: Path,
    ownType: Boolean,
): FileInfo {
    val linkFlag = if (ownType) FILE_FLAG_OPEN_REPARSE_POINT else 0
    val flags = (FILE_FLAG_BACKUP_SEMANTICS or linkFlag).convert<UInt>()
    val handle = openHandle(path, FILE_READ_ATTRIBUTES.convert(), OPEN_EXISTING, flags)
        ?: throw windowsFailure(GetLastError(), path)
    try {
        return handleInfo(handle, path, ownType)
    } finally {
        CloseHandle(handle)
    }
}

/**
 * The info of the file open as [handle], at [path], a reparse point typed [FileType.Symlink] with [ownType], and a
 * character device or pipe typed as `GetFileType` says. A Windows file has no ctime, no mode beyond the read-only flag
 * and no owner this reads, so those stay null. Unverified on Windows.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun handleInfo(
    handle: HANDLE,
    path: Path,
    ownType: Boolean = false,
): FileInfo = memScoped {
    val streamType = streamTypeOf(handle)
    val data = alloc<BY_HANDLE_FILE_INFORMATION>()
    if (GetFileInformationByHandle(handle, data.ptr) == 0) {
        if (streamType != null) return@memScoped streamInfo(streamType)
        throw windowsFailure(GetLastError(), path)
    }
    FileInfo(
        type = streamType ?: typeOfAttributes(data.dwFileAttributes, ownType),
        size = data.nFileSizeHigh.toLong() shl 32 or data.nFileSizeLow.toLong(),
        modifiedAt = data.ftLastWriteTime.toInstant(),
        changedAt = null,
        permissions = null,
        ownedByMe = null,
        links = data.nNumberOfLinks.toInt(),
    )
}

// Unverified on Windows: the attributes are the link's own, any reparse point counts as a link, and a device the
// attributes cannot tell from a file is Regular.
@OptIn(ExperimentalForeignApi::class, InternalKernApi::class)
internal actual fun ownFileTypeAt(path: Path): FileType? = memScoped {
    val data = alloc<WIN32_FILE_ATTRIBUTE_DATA>()
    if (GetFileAttributesExW(path.toString(), _GET_FILEEX_INFO_LEVELS.GetFileExInfoStandard, data.ptr) == 0) {
        val code = GetLastError()
        if (IoCause.ofWindowsError(code) == IoCause.NotFound) return@memScoped null
        throw windowsFailure(code, path)
    }
    typeOfAttributes(data.dwFileAttributes, ownType = true)
}

/** The type an entry's [attributes] give, a reparse point typed [FileType.Symlink] with [ownType]. */
@OptIn(ExperimentalForeignApi::class)
private fun typeOfAttributes(
    attributes: UInt,
    ownType: Boolean,
): FileType = when {
    ownType && attributes and FILE_ATTRIBUTE_REPARSE_POINT.convert() != 0u -> FileType.Symlink
    attributes and FILE_ATTRIBUTE_DIRECTORY.convert() != 0u -> FileType.Directory
    else -> FileType.Regular
}

/** What `GetFileType` says [handle] is when it is a character device or a pipe; null for a disk file or no answer. */
@OptIn(ExperimentalForeignApi::class)
internal fun streamTypeOf(handle: HANDLE): FileType? = when (GetFileType(handle)) {
    FILE_TYPE_CHAR.convert<UInt>() -> FileType.CharacterDevice
    FILE_TYPE_PIPE.convert<UInt>() -> FileType.Fifo
    else -> null
}

/** The info of a device or pipe [type] whose details Windows does not give: size 0, [NO_TIME] and links null. */
private fun streamInfo(type: FileType): FileInfo = FileInfo(
    type = type,
    size = 0,
    modifiedAt = NO_TIME,
    changedAt = null,
    permissions = null,
    ownedByMe = null,
    links = null,
)

@OptIn(ExperimentalForeignApi::class)
private fun FILETIME.toInstant(): Instant {
    val ticks = (dwHighDateTime.toLong() shl 32 or dwLowDateTime.toLong()) - TICKS_TO_UNIX_EPOCH
    return Instant.fromEpochSeconds(ticks.floorDiv(TICKS_PER_SECOND), ticks.mod(TICKS_PER_SECOND) * NANOS_PER_TICK)
}

// Unverified on Windows.
@OptIn(ExperimentalForeignApi::class)
@InternalKernApi
public actual fun setLastModified(
    path: Path,
    time: Instant,
) {
    val handle = openHandle(path, FILE_WRITE_ATTRIBUTES.convert(), OPEN_EXISTING, FILE_FLAG_BACKUP_SEMANTICS.convert())
        ?: throw windowsFailure(GetLastError(), path)
    try {
        memScoped {
            val ticks = time.epochSeconds * TICKS_PER_SECOND + time.nanosecondsOfSecond / NANOS_PER_TICK +
                TICKS_TO_UNIX_EPOCH
            val written = alloc<FILETIME>()
            written.dwLowDateTime = ticks.toUInt()
            written.dwHighDateTime = (ticks ushr 32).toUInt()
            if (SetFileTime(handle, null, null, written.ptr) == 0) throw windowsFailure(GetLastError(), path)
        }
    } finally {
        CloseHandle(handle)
    }
}
