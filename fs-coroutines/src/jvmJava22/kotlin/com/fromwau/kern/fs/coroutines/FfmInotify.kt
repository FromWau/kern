package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.div
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.lang.invoke.MethodHandle
import java.lang.invoke.VarHandle

// <sys/inotify.h>, <poll.h> and <errno.h>, the same on x86_64 and aarch64.
private const val IN_MODIFY = 0x2
private const val IN_ATTRIB = 0x4
private const val IN_CLOSE_WRITE = 0x8
private const val IN_MOVED_FROM = 0x40
private const val IN_MOVED_TO = 0x80
private const val IN_CREATE = 0x100
private const val IN_DELETE = 0x200
private const val IN_DELETE_SELF = 0x400
private const val IN_MOVE_SELF = 0x800
private const val IN_UNMOUNT = 0x2000
private const val IN_Q_OVERFLOW = 0x4000
private const val IN_IGNORED = 0x8000
private const val IN_MASK_ADD = 0x20000000
private const val IN_NONBLOCK = 0x800
private const val IN_CLOEXEC = 0x80000
private const val POLLIN: Short = 1
private const val EAGAIN = 11
private const val EINTR = 4
private const val ENOENT = 2
private const val ENOTDIR = 20
private const val ENOSPC = 28

private const val WATCHED = IN_CREATE or IN_DELETE or IN_MODIFY or IN_ATTRIB or IN_MOVED_FROM or IN_MOVED_TO or
    IN_CLOSE_WRITE or IN_DELETE_SELF or IN_MOVE_SELF
private const val FOLDER_GONE = IN_DELETE_SELF or IN_MOVE_SELF or IN_IGNORED or IN_UNMOUNT

// On the file itself, so a write through a hard link in a folder not watched is seen, and so are the entries of a
// watched path that is a folder; IN_MASK_ADD keeps a folder's mask when the file is that folder.
private const val FILE_EVENTS = IN_MODIFY or IN_ATTRIB or IN_CLOSE_WRITE or IN_CREATE or IN_DELETE or IN_MOVED_FROM or
    IN_MOVED_TO or IN_MASK_ADD

private const val BUFFER_BYTES = 64L * 1024
private const val EVENT_HEADER = 16L
private const val SLICE_MILLIS = 250

/** The libc calls inotify needs, each capturing errno where it can fail for a reason worth telling apart. */
private object Libc {
    private val linker = Linker.nativeLinker()
    private val errnoState = Linker.Option.captureCallState("errno")
    val stateLayout = Linker.Option.captureStateLayout()
    val errno: VarHandle = stateLayout.varHandle(MemoryLayout.PathElement.groupElement("errno"))

    private fun handle(name: String, descriptor: FunctionDescriptor, vararg options: Linker.Option): MethodHandle =
        linker.downcallHandle(linker.defaultLookup().find(name).orElseThrow(), descriptor, *options)

    val inotifyInit1 = handle("inotify_init1", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
    val inotifyAddWatch = handle(
        "inotify_add_watch",
        FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS, JAVA_INT),
        errnoState,
    )
    val inotifyRmWatch = handle("inotify_rm_watch", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT))
    val read = handle("read", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), errnoState)
    val poll = handle("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), errnoState)
    val close = handle("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT))
}

/** Found by name from the JVM's `openFolderWaker`, on Linux from Java 22 on. */
internal class FfmInotifyOpener : InotifyOpener {
    override fun open(folders: Map<Path, Set<String>>): FolderWatch? = try {
        openInotify(folders)
    } catch (_: IllegalCallerException) {
        // A JDK that blocks native access the app did not grant.
        null
    } catch (_: LinkageError) {
        // The same refusal met while Libc's calls are first looked up, and every time after.
        null
    }
}

/** One inotify instance with a watch on each of [folders] and on each file of its names in it. */
private fun openInotify(folders: Map<Path, Set<String>>): FolderWatch {
    val fd = Libc.inotifyInit1.invokeWithArguments(IN_NONBLOCK or IN_CLOEXEC) as Int
    if (fd < 0) return FolderWatch.Refused
    val arena = Arena.ofShared()
    val state = arena.allocate(Libc.stateLayout)
    val namesByWatch = mutableMapOf<Int, Set<String>>()
    for ((folder, names) in folders) {
        val watch = Libc.inotifyAddWatch.invokeWithArguments(state, fd, arena.allocateFrom(folder.toString()), WATCHED)
            as Int
        if (watch < 0) {
            val errno = Libc.errno.get(state, 0L) as Int
            // A file where a folder on the way belongs is a folder still to be made.
            val missing = errno == ENOENT || errno == ENOTDIR
            val _ = Libc.close.invokeWithArguments(fd)
            arena.close()
            return if (missing) FolderWatch.Missing else FolderWatch.Refused
        }
        // One folder reached by two paths is one watch.
        namesByWatch[watch] = namesByWatch[watch].orEmpty() + names
    }
    val files = folders.flatMap { (folder, names) -> names.map { folder / it } }
    return FfmInotifyWaker(fd, arena, state, namesByWatch, files).apply { watchFiles() }
}

private class FfmInotifyWaker(
    private val fd: Int,
    private val arena: Arena,
    private val state: MemorySegment,
    private val namesByWatch: Map<Int, Set<String>>,
    files: List<Path>,
) : FolderWaker {
    private val buffer = arena.allocate(BUFFER_BYTES, 8)
    private val request = arena.allocate(8, 4)
    private val fileWatches = mutableMapOf<Path, Int>()

    // Allocated once: the arena frees nothing before the watch closes.
    private val fileNames = files.associateWith { arena.allocateFrom(it.toString()) }

    // A file there but not watched because the watch limit is reached would lose writes through its other names.
    private var refused = false

    /** Watches whatever file is at each of [files] now, following a link, and drops a watch on one replaced since. */
    fun watchFiles() {
        for ((file, name) in fileNames) {
            val watch = Libc.inotifyAddWatch.invokeWithArguments(state, fd, name, FILE_EVENTS) as Int
            if (watch < 0 && Libc.errno.get(state, 0L) as Int == ENOSPC) refused = true
            val old = if (watch >= 0) fileWatches.put(file, watch) else fileWatches.remove(file)
            if (old != null && old != watch && old !in fileWatches.values && old !in namesByWatch) {
                val _ = Libc.inotifyRmWatch.invokeWithArguments(fd, old)
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
    private fun waitForEvents(): Int {
        request.set(JAVA_INT, 0, fd)
        request.set(JAVA_SHORT, 4, POLLIN)
        request.set(JAVA_SHORT, 6, 0)
        val ready = Libc.poll.invokeWithArguments(state, request, 1L, SLICE_MILLIS) as Int
        return if (ready < 0 && Libc.errno.get(state, 0L) as Int == EINTR) 0 else ready
    }

    /** What the events ready to read say, or null when none of them is about a watched file or folder. */
    private fun readEvents(): Wake? {
        val length = Libc.read.invokeWithArguments(state, fd, buffer, BUFFER_BYTES) as Long
        if (length < 0) {
            val errno = Libc.errno.get(state, 0L) as Int
            return if (errno == EAGAIN || errno == EINTR) null else Wake.Refused
        }
        val wakes = mutableSetOf<Wake>()
        var named = false
        var offset = 0L
        while (offset < length) {
            val watch = buffer.get(JAVA_INT, offset)
            val mask = buffer.get(JAVA_INT, offset + 4)
            val nameBytes = buffer.get(JAVA_INT, offset + 12)
            when {
                mask and IN_Q_OVERFLOW != 0 -> wakes += Wake.Overflow
                // The file's own watch ends when its last name goes, which its folder tells by name.
                watch !in namesByWatch -> if (mask and IN_IGNORED == 0) wakes += Wake.Changed
                mask and FOLDER_GONE != 0 -> wakes += Wake.FolderGone
                // An event with no name is about a folder itself, such as its permissions, which a lookup of the
                // file goes through.
                nameBytes == 0 -> wakes += Wake.Look
                buffer.getString(offset + EVENT_HEADER) in namesByWatch[watch].orEmpty() -> {
                    wakes += Wake.Changed
                    named = true
                }
            }
            offset += EVENT_HEADER + nameBytes
        }
        if (named) watchFiles()
        return when {
            Wake.FolderGone in wakes -> Wake.FolderGone
            Wake.Overflow in wakes -> Wake.Overflow
            Wake.Changed in wakes -> Wake.Changed
            Wake.Look in wakes -> Wake.Look
            else -> null
        }
    }

    override fun close() {
        val _ = Libc.close.invokeWithArguments(fd)
        arena.close()
    }
}
