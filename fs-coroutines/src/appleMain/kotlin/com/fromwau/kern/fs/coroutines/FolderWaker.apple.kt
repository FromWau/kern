package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.div
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
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
import platform.posix.EMFILE
import platform.posix.ENFILE
import platform.posix.ENOENT
import platform.posix.ENOTDIR
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
            // A file where a folder on the way belongs is a folder still to be made.
            folderFd < 0 -> if (errno == ENOENT || errno == ENOTDIR) FolderWatch.Missing else FolderWatch.Refused
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

    // A file there but not watched because the process has no descriptor left would lose its writes in place.
    private var refused = false

    init {
        watchFiles()
    }

    /** Watches whatever file is at each of [files] now, following a link; closing an old descriptor drops its watch. */
    private fun watchFiles() {
        files.forEachIndexed { index, file ->
            // Opened before the old one closes, so a write between the two still reaches a watch. O_NONBLOCK, since
            // opening a FIFO would otherwise wait for a writer.
            val fd = open(file.toString(), O_EVTONLY or O_NONBLOCK)
            if (fd < 0 && (errno == EMFILE || errno == ENFILE)) refused = true
            if (fileFds[index] >= 0) platform.posix.close(fileFds[index])
            fileFds[index] = if (fd >= 0 && !register(queue, fd)) {
                platform.posix.close(fd)
                refused = true
                -1
            } else {
                fd
            }
        }
    }

    override suspend fun next(): Wake {
        if (refused) return Wake.Refused
        val wake = withContext(WatchWaits) { waitForEvents() } ?: return Wake.Quiet
        watchFiles()
        return if (refused) Wake.Refused else wake
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
        val ready = (0 until count).map { events[it] }
        when {
            ready.any { it.ident.toInt() in folderFds && it.fflags.toInt() and FOLDER_GONE != 0 } -> Wake.FolderGone
            ready.any { it.ident.toInt() in fileFds } -> Wake.Changed
            else -> Wake.Look
        }
    }

    override fun close() {
        fileFds.filter { it >= 0 }.forEach { platform.posix.close(it) }
        folderFds.forEach { platform.posix.close(it) }
        platform.posix.close(queue)
    }
}
