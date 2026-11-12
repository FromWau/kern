package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.div
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import platform.linux.IN_ATTRIB
import platform.linux.IN_CLOEXEC
import platform.linux.IN_CLOSE_WRITE
import platform.linux.IN_CREATE
import platform.linux.IN_DELETE
import platform.linux.IN_DELETE_SELF
import platform.linux.IN_IGNORED
import platform.linux.IN_MASK_ADD
import platform.linux.IN_MODIFY
import platform.linux.IN_MOVED_FROM
import platform.linux.IN_MOVED_TO
import platform.linux.IN_MOVE_SELF
import platform.linux.IN_NONBLOCK
import platform.linux.IN_Q_OVERFLOW
import platform.linux.IN_UNMOUNT
import platform.linux.inotify_add_watch
import platform.linux.inotify_event
import platform.linux.inotify_init1
import platform.linux.inotify_rm_watch
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.ENOENT
import platform.posix.ENOSPC
import platform.posix.ENOTDIR
import platform.posix.POLLIN
import platform.posix.close
import platform.posix.errno
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.read

// Room for many events at once; one event is at most its header and a name of NAME_MAX + 1 bytes.
private const val BUFFER_BYTES = 64 * 1024

private const val WATCHED = IN_CREATE or IN_DELETE or IN_MODIFY or IN_ATTRIB or IN_MOVED_FROM or IN_MOVED_TO or
    IN_CLOSE_WRITE or IN_DELETE_SELF or IN_MOVE_SELF

private const val FOLDER_GONE = IN_DELETE_SELF or IN_MOVE_SELF or IN_IGNORED or IN_UNMOUNT

// On the file itself, so a write through a hard link in a folder not watched is seen, and so are the entries of a
// watched path that is a folder; IN_MASK_ADD keeps a folder's mask when the file is that folder.
private const val FILE_EVENTS = IN_MODIFY or IN_ATTRIB or IN_CLOSE_WRITE or IN_CREATE or IN_DELETE or IN_MOVED_FROM or
    IN_MOVED_TO or IN_MASK_ADD

/**
 * One inotify instance with a watch on each of [folders] and on each file of its names in it; [FolderWatch.Refused]
 * when inotify refuses a folder, as when the watch limit is reached.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    val fd = inotify_init1(IN_NONBLOCK or IN_CLOEXEC)
    if (fd < 0) return FolderWatch.Refused
    val namesByWatch = mutableMapOf<Int, Set<String>>()
    for ((folder, names) in folders) {
        val watch = inotify_add_watch(fd, folder.toString(), WATCHED.convert())
        if (watch < 0) {
            // A file where a folder on the way belongs is a folder still to be made.
            val missing = errno == ENOENT || errno == ENOTDIR
            close(fd)
            return if (missing) FolderWatch.Missing else FolderWatch.Refused
        }
        // One folder reached by two paths is one watch.
        namesByWatch[watch] = namesByWatch[watch].orEmpty() + names
    }
    val files = folders.flatMap { (folder, names) -> names.map { folder / it } }
    return InotifyWaker(fd, namesByWatch, files).apply { watchFiles() }
}

@OptIn(ExperimentalForeignApi::class)
private class InotifyWaker(
    private val fd: Int,
    private val namesByWatch: Map<Int, Set<String>>,
    private val files: List<Path>,
) : FolderWaker {
    private val fileWatches = mutableMapOf<Path, Int>()

    // A file there but not watched because the watch limit is reached would lose writes through its other names.
    private var refused = false

    /** Watches whatever file is at each of [files] now, following a link, and drops a watch on one replaced since. */
    fun watchFiles() {
        for (file in files) {
            val watch = inotify_add_watch(fd, file.toString(), FILE_EVENTS.convert())
            if (watch < 0 && errno == ENOSPC) refused = true
            val old = if (watch >= 0) fileWatches.put(file, watch) else fileWatches.remove(file)
            if (old != null && old != watch && old !in fileWatches.values && old !in namesByWatch) {
                val _ = inotify_rm_watch(fd, old)
            }
        }
    }

    override suspend fun next(): Wake {
        if (refused) return Wake.Refused
        val ready = withContext(WatchWaits) { waitForEvents() }
        if (ready < 0) return Wake.Refused
        if (ready == 0) return Wake.Quiet
        return readEvents() ?: Wake.Quiet
    }

    /** poll()'s answer for one slice: above zero when events are ready, below zero when it failed. */
    private fun waitForEvents(): Int = memScoped {
        val request = alloc<pollfd>()
        request.fd = fd
        request.events = POLLIN.convert()
        val ready = poll(request.ptr, 1u, WAIT_SLICE_MILLIS)
        if (ready < 0 && errno == EINTR) 0 else ready
    }

    /** What the events ready to read say, or null when none of them is about a watched file or folder. */
    private fun readEvents(): Wake? = memScoped {
        val buffer = allocArray<ByteVar>(BUFFER_BYTES)
        val length = read(fd, buffer, BUFFER_BYTES.convert())
        if (length < 0) return if (errno == EAGAIN || errno == EINTR) null else Wake.Refused
        val wakes = mutableSetOf<Wake>()
        var named = false
        var offset = 0L
        while (offset < length) {
            val event = (buffer + offset)!!.reinterpret<inotify_event>().pointed
            val mask = event.mask.toInt()
            val header = sizeOf<inotify_event>()
            when {
                mask and IN_Q_OVERFLOW != 0 -> wakes += Wake.Overflow
                // The file's own watch ends when its last name goes, which its folder tells by name.
                event.wd !in namesByWatch -> if (mask and IN_IGNORED == 0) wakes += Wake.Changed
                mask and FOLDER_GONE != 0 -> wakes += Wake.FolderGone
                // An event with no name is about a folder itself, such as its permissions, which a lookup of the
                // file goes through.
                event.len == 0u -> wakes += Wake.Look
                (buffer + offset + header)!!.toKString() in namesByWatch[event.wd].orEmpty() -> {
                    wakes += Wake.Changed
                    named = true
                }
            }
            offset += header + event.len.toLong()
        }
        if (named) watchFiles()
        when {
            Wake.FolderGone in wakes -> Wake.FolderGone
            Wake.Overflow in wakes -> Wake.Overflow
            Wake.Changed in wakes -> Wake.Changed
            Wake.Look in wakes -> Wake.Look
            else -> null
        }
    }

    override fun close() {
        platform.posix.close(fd)
    }
}
