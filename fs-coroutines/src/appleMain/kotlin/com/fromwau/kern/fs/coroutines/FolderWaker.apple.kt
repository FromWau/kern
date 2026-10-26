package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.div
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.darwin.EVFILT_VNODE
import platform.darwin.EV_ADD
import platform.darwin.EV_CLEAR
import platform.darwin.NOTE_ATTRIB
import platform.darwin.NOTE_DELETE
import platform.darwin.NOTE_EXTEND
import platform.darwin.NOTE_LINK
import platform.darwin.NOTE_RENAME
import platform.darwin.NOTE_REVOKE
import platform.darwin.NOTE_WRITE
import platform.darwin.kevent
import platform.darwin.kqueue
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.O_EVTONLY
import platform.posix.O_NONBLOCK
import platform.posix.errno
import platform.posix.open
import platform.posix.timespec

private const val EVENTS_AT_ONCE = 8

private const val NOTES = NOTE_WRITE or NOTE_EXTEND or NOTE_ATTRIB or NOTE_LINK or NOTE_DELETE or NOTE_RENAME or
    NOTE_REVOKE

private const val FOLDER_GONE = NOTE_DELETE or NOTE_RENAME or NOTE_REVOKE

/**
 * One kqueue watching each of [folders] and each file of its names in it, since a folder's own events tell of names
 * added and removed but not of a file written in place; [FolderWatch.Refused] when kqueue refuses one. Unverified on
 * Apple hardware.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    val queue = kqueue()
    if (queue < 0) return FolderWatch.Refused
    val folderFds = mutableListOf<Int>()
    for (folder in folders.keys) {
        val folderFd = open(folder.toString(), O_EVTONLY)
        val refusal = when {
            folderFd < 0 -> if (errno == ENOENT) FolderWatch.Missing else FolderWatch.Refused
            !register(queue, folderFd) -> FolderWatch.Refused
            else -> null
        }
        if (refusal != null) {
            if (folderFd >= 0) platform.posix.close(folderFd)
            folderFds.forEach { platform.posix.close(it) }
            platform.posix.close(queue)
            return refusal
        }
        folderFds += folderFd
    }
    val files = folders.flatMap { (folder, names) -> names.map { folder / it } }
    return KqueueWaker(queue, folderFds, files)
}

/** Adds [fd] to [queue] for every change kqueue tells of a file; false when it could not. */
@OptIn(ExperimentalForeignApi::class)
private fun register(queue: Int, fd: Int): Boolean = memScoped {
    val change = alloc<kevent>()
    change.ident = fd.convert()
    change.filter = EVFILT_VNODE.convert()
    change.flags = (EV_ADD or EV_CLEAR).convert()
    change.fflags = NOTES.convert()
    change.data = 0
    change.udata = null
    kevent(queue, change.ptr, 1, null, 0, null) == 0
}

@OptIn(ExperimentalForeignApi::class)
private class KqueueWaker(
    private val queue: Int,
    private val folderFds: List<Int>,
    private val files: List<Path>,
) : FolderWaker {
    private val fileFds = IntArray(files.size) { -1 }

    init {
        watchFiles()
    }

    /** Watches whatever file is at each of [files] now, following a link; closing an old descriptor drops its watch. */
    private fun watchFiles() {
        files.forEachIndexed { index, file ->
            if (fileFds[index] >= 0) platform.posix.close(fileFds[index])
            // O_NONBLOCK, since opening a FIFO would otherwise wait for a writer.
            val fd = open(file.toString(), O_EVTONLY or O_NONBLOCK)
            fileFds[index] = if (fd >= 0 && !register(queue, fd)) {
                platform.posix.close(fd)
                -1
            } else {
                fd
            }
        }
    }

    override suspend fun next(): Wake {
        val wake = withContext(Dispatchers.IO) { waitForEvents() } ?: return Wake.Quiet
        watchFiles()
        return wake
    }

    /** What the events of one slice say, or null when none came. */
    private fun waitForEvents(): Wake? = memScoped {
        val events = allocArray<kevent>(EVENTS_AT_ONCE)
        val timeout = alloc<timespec>()
        timeout.tv_sec = 0
        timeout.tv_nsec = WAIT_SLICE_MILLIS * 1_000_000L
        val count = kevent(queue, null, 0, events, EVENTS_AT_ONCE, timeout.ptr)
        if (count < 0) return if (errno == EINTR) null else Wake.Refused
        if (count == 0) return null
        val folderGone = (0 until count).any { index ->
            val event = events[index]
            event.ident.toInt() in folderFds && event.fflags.toInt() and FOLDER_GONE != 0
        }
        if (folderGone) Wake.FolderGone else Wake.Event
    }

    override fun close() {
        fileFds.filter { it >= 0 }.forEach { platform.posix.close(it) }
        folderFds.forEach { platform.posix.close(it) }
        platform.posix.close(queue)
    }
}
