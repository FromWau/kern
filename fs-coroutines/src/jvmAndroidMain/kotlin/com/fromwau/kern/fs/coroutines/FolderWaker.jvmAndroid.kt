package com.fromwau.kern.fs.coroutines

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.io.files.Path
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds.ENTRY_CREATE
import java.nio.file.StandardWatchEventKinds.ENTRY_DELETE
import java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY
import java.nio.file.StandardWatchEventKinds.OVERFLOW
import java.nio.file.WatchKey
import java.nio.file.WatchService
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

// How long a wait goes at most before it checks the folders are still at their paths and wakes for a lookup.
private val SLICE = 250.milliseconds

/**
 * One `WatchService` with each of [folders] registered on it; [FolderWatch.Refused] on macOS, whose `WatchService`
 * polls every few seconds, or when a folder cannot be registered.
 */
internal actual fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch {
    if (System.getProperty("os.name").orEmpty().startsWith("Mac")) return FolderWatch.Refused
    val service = try {
        FileSystems.getDefault().newWatchService()
    } catch (_: Exception) {
        return FolderWatch.Refused
    }
    return try {
        val watched = mutableMapOf<WatchKey, Watched>()
        for ((folder, names) in folders) {
            val path = Paths.get(folder.toString())
            // Read first, so a folder swapped in after the read is told apart from the one registered.
            val identity = folderIdentity(path)
            val key = path.register(service, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY)
            // One folder reached by two paths is one key.
            watched[key] = watched[key]?.let { it.copy(names = it.names + names) } ?: Watched(path, identity, names)
        }
        WatchServiceWaker(service, watched)
    } catch (failure: Exception) {
        service.close()
        if (failure is NoSuchFileException) FolderWatch.Missing else FolderWatch.Refused
    }
}

/** The folder's file key, or its path where the file system has no keys, as on Windows. */
private fun folderIdentity(folder: java.nio.file.Path): Any =
    Files.readAttributes(folder, BasicFileAttributes::class.java).fileKey() ?: folder

/** A registered [folder], as [identity] said it was when registered, and the file [names] in it to wake for. */
private data class Watched(
    val folder: java.nio.file.Path,
    val identity: Any,
    val names: Set<String>,
) {
    fun stillThere(): Boolean = try {
        folderIdentity(folder) == identity
    } catch (_: Exception) {
        false
    }
}

private class WatchServiceWaker(
    private val service: WatchService,
    private val watched: Map<WatchKey, Watched>,
) : FolderWaker {
    override suspend fun next(): Wake = runInterruptible(Dispatchers.IO) { awaitSlice() }

    /** Blocks until an event about a watched file, or at most one [SLICE]. */
    private fun awaitSlice(): Wake {
        val sliceEnd = TimeSource.Monotonic.markNow() + SLICE
        while (true) {
            val left = -sliceEnd.elapsedNow()
            val key = if (left.isPositive()) service.poll(left.inWholeMilliseconds, TimeUnit.MILLISECONDS) else null
            if (key == null) break
            wakeFor(key)?.let { return it }
        }
        // The service tells nothing of a folder moved away or of a change to its mode or owner, so each slice ends in
        // a lookup, which answers only what differs.
        return if (watched.values.all { it.stillThere() }) Wake.Event else Wake.FolderGone
    }

    /** What the events [key] holds say, or null when none of them is about a watched file. */
    private fun wakeFor(key: WatchKey): Wake? {
        val names = watched[key]?.names.orEmpty()
        val events = key.pollEvents()
        return when {
            !key.reset() -> Wake.FolderGone
            events.any { it.kind() == OVERFLOW } -> Wake.Overflow
            events.any { it.context()?.toString() in names } -> Wake.Event
            else -> null
        }
    }

    override fun close() {
        service.close()
    }
}
