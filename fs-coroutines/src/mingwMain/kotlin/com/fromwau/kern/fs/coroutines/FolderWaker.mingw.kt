package com.fromwau.kern.fs.coroutines

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.free
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.value
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.posix.memset
import platform.windows.CancelIoEx
import platform.windows.CloseHandle
import platform.windows.CreateEventW
import platform.windows.CreateFileW
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_OPERATION_ABORTED
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_FLAG_BACKUP_SEMANTICS
import platform.windows.FILE_FLAG_OVERLAPPED
import platform.windows.FILE_LIST_DIRECTORY
import platform.windows.FILE_NOTIFY_CHANGE_ATTRIBUTES
import platform.windows.FILE_NOTIFY_CHANGE_CREATION
import platform.windows.FILE_NOTIFY_CHANGE_DIR_NAME
import platform.windows.FILE_NOTIFY_CHANGE_FILE_NAME
import platform.windows.FILE_NOTIFY_CHANGE_LAST_WRITE
import platform.windows.FILE_NOTIFY_CHANGE_SECURITY
import platform.windows.FILE_NOTIFY_CHANGE_SIZE
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GetLastError
import platform.windows.GetOverlappedResult
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.OPEN_EXISTING
import platform.windows.OVERLAPPED
import platform.windows.ReadDirectoryChangesW
import platform.windows.ResetEvent
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForMultipleObjects

private const val CHANGES = FILE_NOTIFY_CHANGE_FILE_NAME or FILE_NOTIFY_CHANGE_DIR_NAME or
    FILE_NOTIFY_CHANGE_ATTRIBUTES or FILE_NOTIFY_CHANGE_SIZE or FILE_NOTIFY_CHANGE_LAST_WRITE or
    FILE_NOTIFY_CHANGE_CREATION or FILE_NOTIFY_CHANGE_SECURITY

// 64 KiB, the most ReadDirectoryChangesW takes for a folder on a network share; it needs the buffer DWORD-aligned.
private const val BUFFER_WORDS = 16_384

// FILE_NOTIFY_INFORMATION: NextEntryOffset, Action and FileNameLength, each a DWORD, then the name in UTF-16.
private const val NAME_LENGTH_AT = 8
private const val NAME_AT = 12

/**
 * A `ReadDirectoryChangesW` read on each of [folders], all waited on together, which wakes for the file names in each.
 * [FolderWatch.Refused] when Windows refuses one.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    val reads = mutableListOf<FolderChanges>()
    for ((folder, names) in folders) {
        val directory = CreateFileW(
            folder.toString(),
            FILE_LIST_DIRECTORY.convert(),
            (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).convert(),
            null,
            OPEN_EXISTING.convert(),
            (FILE_FLAG_BACKUP_SEMANTICS or FILE_FLAG_OVERLAPPED).convert(),
            null,
        )
        if (directory == null || directory == INVALID_HANDLE_VALUE) {
            val error = GetLastError().toInt()
            reads.forEach { it.close() }
            val missing = error == ERROR_FILE_NOT_FOUND || error == ERROR_PATH_NOT_FOUND
            return if (missing) FolderWatch.Missing else FolderWatch.Refused
        }
        val read = FolderChanges.startOrNull(names, directory)
        if (read == null) {
            reads.forEach { it.close() }
            return FolderWatch.Refused
        }
        reads += read
    }
    return DirectoryChangesWaker(reads)
}

/** The [reads], one for each watched folder. */
@OptIn(ExperimentalForeignApi::class)
private class DirectoryChangesWaker(private val reads: List<FolderChanges>) : FolderWaker {
    override suspend fun next(): Wake {
        val waited = withContext(WatchWaits) { waitForAny() }
        val woken = (waited - WAIT_OBJECT_0).toInt()
        return when {
            woken in reads.indices -> reads[woken].take() ?: Wake.Quiet
            waited == WAIT_TIMEOUT.convert<UInt>() -> Wake.Quiet
            else -> Wake.Refused
        }
    }

    /** WaitForMultipleObjects' answer for one slice, waiting for any one of the reads to finish. */
    private fun waitForAny(): UInt = memScoped {
        val array = allocArray<HANDLEVar>(reads.size)
        reads.forEachIndexed { index, read -> array[index] = read.finished }
        WaitForMultipleObjects(reads.size.convert(), array, 0, WAIT_SLICE_MILLIS.toUInt())
    }

    override fun close() {
        reads.forEach { it.close() }
    }
}

/** One overlapped read of the changes in a folder through [directory], which signals [finished] when it ends. */
@OptIn(ExperimentalForeignApi::class)
private class FolderChanges private constructor(
    private val names: Set<String>,
    private val directory: HANDLE,
    val finished: HANDLE,
) : AutoCloseable {
    private val overlapped = nativeHeap.alloc<OVERLAPPED>()
    private val buffer = nativeHeap.allocArray<UIntVar>(BUFFER_WORDS)
    private var pending = false

    /** Asks for the next changes; false when Windows refuses. */
    fun read(): Boolean {
        memset(overlapped.ptr, 0, sizeOf<OVERLAPPED>().convert())
        overlapped.hEvent = finished
        val _ = ResetEvent(finished)
        val bufferBytes = (BUFFER_WORDS * Int.SIZE_BYTES).convert<UInt>()
        val started = ReadDirectoryChangesW(
            directory,
            buffer,
            bufferBytes,
            0,
            CHANGES.convert(),
            null,
            overlapped.ptr,
            null,
        )
        pending = started != 0
        return pending
    }

    /**
     * What the finished read says, asking for the next: [Wake.Changed] when it names a watched file, [Wake.Overflow]
     * when more changed than the buffer holds or the read was cancelled, null when it names none, [Wake.Refused] when
     * Windows refuses.
     */
    fun take(): Wake? = memScoped {
        val bytes = alloc<UIntVar>()
        pending = false
        if (GetOverlappedResult(directory, overlapped.ptr, bytes.ptr, 0) == 0) {
            // Windows cancels a read when the thread that asked for it ends, so what it would have told is unknown.
            if (GetLastError().toInt() != ERROR_OPERATION_ABORTED) return Wake.Refused
            return if (read()) Wake.Overflow else Wake.Refused
        }
        val wake = when {
            bytes.value == 0u -> Wake.Overflow
            changedNames().any { changed -> names.any { it.equals(changed, ignoreCase = true) } } -> Wake.Changed
            else -> null
        }
        if (!read()) Wake.Refused else wake
    }

    /** The names in the finished read's buffer, one for each change it holds. */
    private fun changedNames(): List<String> {
        val names = mutableListOf<String>()
        var entry: CPointer<ByteVar> = buffer.reinterpret()
        while (true) {
            val nameBytes = (entry + NAME_LENGTH_AT)!!.reinterpret<UIntVar>()[0].toInt()
            val chars = (entry + NAME_AT)!!.reinterpret<UShortVar>()
            names += CharArray(nameBytes / 2) { chars[it].toInt().toChar() }.concatToString()
            val next = entry.reinterpret<UIntVar>()[0].toLong()
            if (next == 0L) return names
            entry = (entry + next)!!
        }
    }

    override fun close() {
        if (pending) {
            val _ = CancelIoEx(directory, overlapped.ptr)
            memScoped {
                // The read must be over before its buffer is freed.
                val _ = GetOverlappedResult(directory, overlapped.ptr, alloc<UIntVar>().ptr, 1)
            }
        }
        val _ = CloseHandle(directory)
        val _ = CloseHandle(finished)
        nativeHeap.free(buffer)
        nativeHeap.free(overlapped)
    }

    companion object {
        /** The first read for [names] through [directory]; null, with [directory] closed, when refused. */
        fun startOrNull(names: Set<String>, directory: HANDLE): FolderChanges? {
            val finished = CreateEventW(null, 1, 0, null)
            if (finished == null) {
                val _ = CloseHandle(directory)
                return null
            }
            val changes = FolderChanges(names, directory, finished)
            if (changes.read()) return changes
            changes.close()
            return null
        }
    }
}
