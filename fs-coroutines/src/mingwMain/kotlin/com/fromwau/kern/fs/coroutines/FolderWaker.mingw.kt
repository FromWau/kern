package com.fromwau.kern.fs.coroutines

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.set
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.windows.ERROR_FILE_NOT_FOUND
import platform.windows.ERROR_PATH_NOT_FOUND
import platform.windows.FILE_ATTRIBUTE_DIRECTORY
import platform.windows.FILE_NOTIFY_CHANGE_ATTRIBUTES
import platform.windows.FILE_NOTIFY_CHANGE_CREATION
import platform.windows.FILE_NOTIFY_CHANGE_DIR_NAME
import platform.windows.FILE_NOTIFY_CHANGE_FILE_NAME
import platform.windows.FILE_NOTIFY_CHANGE_LAST_WRITE
import platform.windows.FILE_NOTIFY_CHANGE_SIZE
import platform.windows.FindCloseChangeNotification
import platform.windows.FindFirstChangeNotificationW
import platform.windows.FindNextChangeNotification
import platform.windows.GetFileAttributesW
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.HANDLEVar
import platform.windows.INVALID_FILE_ATTRIBUTES
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.WAIT_OBJECT_0
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForMultipleObjects

private const val CHANGES = FILE_NOTIFY_CHANGE_FILE_NAME or FILE_NOTIFY_CHANGE_DIR_NAME or
    FILE_NOTIFY_CHANGE_ATTRIBUTES or FILE_NOTIFY_CHANGE_SIZE or FILE_NOTIFY_CHANGE_LAST_WRITE or
    FILE_NOTIFY_CHANGE_CREATION

/**
 * A change notification on each of [folders], all waited on together; one names no file, so any change in its folder
 * wakes it. [FolderWatch.Refused] when Windows refuses one. Unverified on Windows.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    val handles = mutableListOf<HANDLE>()
    for (folder in folders.keys) {
        val handle = FindFirstChangeNotificationW(folder.toString(), 0, CHANGES.convert())
        if (handle == null || handle == INVALID_HANDLE_VALUE) {
            val missing = GetLastError().toInt().let { it == ERROR_FILE_NOT_FOUND || it == ERROR_PATH_NOT_FOUND }
            handles.forEach { val _ = FindCloseChangeNotification(it) }
            return if (missing) FolderWatch.Missing else FolderWatch.Refused
        }
        handles += handle
    }
    return ChangeNotificationWaker(handles, folders.keys.toList())
}

/** The notifications [handles], one for each of [folders] in the same order. */
@OptIn(ExperimentalForeignApi::class)
private class ChangeNotificationWaker(
    private val handles: List<HANDLE>,
    private val folders: List<Path>,
) : FolderWaker {
    override suspend fun next(): Wake {
        val waited = withContext(Dispatchers.IO) { waitForAny() }
        val woken = (waited - WAIT_OBJECT_0).toInt()
        return when {
            woken in handles.indices -> when {
                FindNextChangeNotification(handles[woken]) == 0 -> Wake.Refused
                allFoldersThere() -> Wake.Event
                else -> Wake.FolderGone
            }
            // A folder moved away may tell nothing, so they are looked for between changes.
            waited == WAIT_TIMEOUT.convert<UInt>() -> if (allFoldersThere()) Wake.Quiet else Wake.FolderGone
            else -> Wake.Refused
        }
    }

    /** WaitForMultipleObjects' answer for one slice, waiting for any one of [handles]. */
    private fun waitForAny(): UInt = memScoped {
        val array = allocArray<HANDLEVar>(handles.size)
        handles.forEachIndexed { index, handle -> array[index] = handle }
        WaitForMultipleObjects(handles.size.convert(), array, 0, WAIT_SLICE_MILLIS.toUInt())
    }

    private fun allFoldersThere(): Boolean = folders.all { folder ->
        val attributes = GetFileAttributesW(folder.toString())
        attributes != INVALID_FILE_ATTRIBUTES && attributes and FILE_ATTRIBUTE_DIRECTORY.convert() != 0u
    }

    override fun close() {
        handles.forEach { val _ = FindCloseChangeNotification(it) }
    }
}
