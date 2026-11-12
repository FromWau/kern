package com.fromwau.kern.fs.coroutines

import kotlinx.coroutines.runInterruptible
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// How long a wait goes at most before it wakes for a lookup.
private val SLICE = 250.milliseconds

/**
 * Linux's inotify reached through FFM where the JDK has it, as native Linux watches; elsewhere one `WatchService` with
 * each of [folders] registered on it. [FolderWatch.Refused] on macOS, whose `WatchService` polls every few seconds, or
 * when a folder cannot be registered.
 */
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    inotify?.open(folders)?.let { return it }
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) return FolderWatch.Refused
    val service = try {
        FileSystems.getDefault().newWatchService()
    } catch (_: Exception) {
        return FolderWatch.Refused
    }
    return try {
        val watched = mutableMapOf<WatchKey, Set<String>>()
        for ((folder, names) in folders) {
            val key = Paths.get(folder.toString()).register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            // One folder reached by two paths is one key.
            watched[key] = watched[key].orEmpty() + names
        }
        WatchServiceWaker(service, watched)
    } catch (_: Exception) {
        service.close()
        // A file where a folder on the way belongs is a folder still to be made, as one not there is.
        if (folders.keys.any { !File(it.toString()).isDirectory }) FolderWatch.Missing else FolderWatch.Refused
    }
}

/** Opens an inotify watch through FFM; null when the JDK refuses the native call, so the `WatchService` is used. */
internal fun interface InotifyOpener {
    fun open(folders: Map<Path, Set<String>>): FolderWatch?
}

// FfmInotifyOpener is compiled for Java 22 into the jar's META-INF/versions/22. Android's java.specification.version
// is 0.9, so it never looks.
private val inotify: InotifyOpener? by lazy {
    val feature = System.getProperty("java.specification.version")?.toIntOrNull() ?: 0
    if (System.getProperty("os.name") != "Linux" || feature < 22) return@lazy null
    try {
        Class.forName("com.fromwau.kern.fs.coroutines.FfmInotifyOpener").getDeclaredConstructor().newInstance()
            as InotifyOpener
    } catch (_: ReflectiveOperationException) {
        null
    } catch (_: LinkageError) {
        null
    }
}

private class WatchServiceWaker(
    private val service: WatchService,
    private val watched: Map<WatchKey, Set<String>>,
) : FolderWaker {
    override suspend fun next(): Wake = runInterruptible(WatchWaits) { awaitSlice() }

    /** Blocks until an event about a watched file, or at most one [SLICE]. */
    private fun awaitSlice(): Wake {
        val sliceEnd = TimeSource.Monotonic.markNow() + SLICE
        while (true) {
            val left = -sliceEnd.elapsedNow()
            val key = if (left.isPositive()) service.poll(left.inWholeMilliseconds, TimeUnit.MILLISECONDS) else null
            if (key == null) break
            wakeFor(key)?.let { return it }
        }
        // The service tells nothing of a change to a folder's mode or owner, so each slice ends in a lookup, which
        // answers only what differs.
        return Wake.Look
    }

    /** What the events [key] holds say, or null when none of them is about a watched file. */
    private fun wakeFor(key: WatchKey): Wake? {
        val names = watched[key].orEmpty()
        val events = key.pollEvents()
        return when {
            !key.reset() -> Wake.FolderGone
            events.any { it.kind() == OVERFLOW } -> Wake.Overflow
            events.any { it.context()?.toString() in names } -> Wake.Changed
            else -> null
        }
    }

    override fun close() {
        service.close()
    }
}
