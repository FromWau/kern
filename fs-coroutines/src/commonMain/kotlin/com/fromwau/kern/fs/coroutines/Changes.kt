package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.Permissions
import com.fromwau.kern.fs.canonicalPath
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.linkChain
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlin.time.Duration

/**
 * What [info] answers for this path now, and again each time that answer changes, for as long as you collect.
 *
 * ```kotlin
 * config.changes(Watch.System()).collect { change ->
 *     change.onSuccess { reload() }.onError { println("$config: $it") }
 * }
 * share.changes(Watch.Poll(5.seconds)).collect { change -> change.onSuccess { reload() } }
 * ```
 *
 * The first answer comes when collection starts, not when you call this. A collector launched on the same thread
 * before code that does not suspend sees nothing until that code suspends. Launched with
 * `start = CoroutineStart.UNDISPATCHED`, it collects the first answer before `launch` returns. After it, an answer
 * comes whenever it differs from the last one: another [FileInfo], an error where there was info or the reverse, or
 * another error. An error never ends the flow, so a deleted file is one [FileError.NotFound], and a file made there
 * again is info once more. Changes close together may come as one answer. A write that leaves the info as it was, as
 * a same-size write within one tick of a file system that keeps whole seconds does, gives no answer here; to act on
 * every write, collect a [FileWatcher].
 *
 * Under [Watch.System] the operating system says when the file changed: inotify on Linux, also on the JVM from Java 22
 * on, kqueue on Apple, `ReadDirectoryChangesW` on Windows, and `WatchService` on Android and other JVMs. With inotify
 * and kqueue the file itself is watched too, so a write through a hard link elsewhere is seen. A missing folder, or one
 * deleted or moved away while watched, is waited for through a watch on the nearest folder above it that is there. It
 * looks every [Watch.System.fallback] instead only after the system refused to watch or a root such as a drive was
 * missing, and on the JVM on macOS, whose `WatchService` polls at a pace you cannot set. Where the system watch
 * misses changes, as on a network file system, use [Watch.Poll].
 *
 * A symlink is watched in the folder of every link in its chain and in the folder of the file the chain ends at, up
 * to the 40 links Linux follows, each folder once however the chain spells it. All of them share one system watch per
 * collector, such as one inotify instance. The chain is found again after each answer, so any link in it pointed
 * elsewhere moves the watch, and a folder the chain no longer passes through is no longer watched.
 *
 * A link to a folder on the way, such as `current -> releases/v1`, is followed too. The watched folders are checked
 * again after each change and each quarter second, both where their real paths lead and whether another folder was put
 * at a path, as `mv top top.old; mv new top` does. Either moves the watch, with an answer when that changes what [info]
 * answers. When the system refuses to watch any of these folders, it looks every [Watch.System.fallback] instead.
 *
 * A file on procfs or sysfs keeps its size and times while its text moves, so neither watch could ever tell it changed:
 * there the only answer is [FileError.Unwatchable], and the flow ends. Read such a file with [textChanges] and
 * [TextWatch.Read].
 *
 * @param watch how a change is noticed: the system's watch, or a lookup on a timer.
 * @return a cold flow: each collector watches on its own, and what it watches with is released once it stops.
 */
public fun Path.changes(watch: Watch): Flow<Result<FileInfo, FileError>> = everyChange(watch).distinctUntilChanged()

/** An answer each time this path may have changed: what [changes] answers, and the same info again for each write. */
internal fun Path.everyChange(watch: Watch): Flow<Result<FileInfo, FileError>> {
    val watched = when (watch) {
        is Watch.System -> changes(watch.fallback, ::openFolderWaker)
        // After a refusal no watch is asked for again, so every answer comes from a lookup.
        is Watch.Poll -> changes(watch.every, openWaker = { FolderWatch.Refused })
    }
    return flow {
        val pseudo = pseudoFileSystemOf(this@everyChange)
        if (pseudo == null) emitAll(watched) else emit(Err(FileError.Unwatchable(this@everyChange, pseudo)))
    }
}

/** [changes] watching through what [openWaker] opens for the folders to watch, each with the file names in it. */
@OptIn(InternalKernApi::class)
internal fun Path.changes(
    pollEvery: Duration,
    openWaker: (folders: Map<Path, Set<String>>) -> FolderWatch,
): Flow<Result<FileInfo, FileError>> {
    require(pollEvery.isPositive()) { "pollEvery must be positive, was $pollEvery" }
    return flow {
        var last: Result<FileInfo, FileError>? = null
        // Each folder's trust as last seen, so a folder that appears or cannot be looked up for a while is no answer.
        var seenTrust = emptyMap<Path, FolderTrust>()

        // A change the system told of is answered even when the info looks the same, as a same-size write in the same
        // time tick of a file system with coarse times does.
        suspend fun look(folders: Set<Path>, told: Boolean = false) {
            val trust = folders.mapNotNull { folder -> folder.trust()?.let { folder to it } }.toMap()
            val trustChanged = trust.any { (folder, current) -> seenTrust[folder].let { it != null && it != current } }
            val now = info()
            seenTrust = seenTrust + trust
            if (now != last || trustChanged || told) {
                last = now
                emit(now)
            }
        }

        var refused = false
        while (true) {
            val chain = linkChain()
            val folders = watchedFolders(chain)
            // Read before the watch is set, so a folder put at its path while it is set up is told apart.
            val identities = folders.keys.map { it.folderIdentity() }

            // A link on the way to a watched folder, such as `current -> releases/v1`, tells its folder nothing when
            // pointed elsewhere, nor does a folder further up moved away, so both are checked after every wake.
            fun unmoved() = linkChain() == chain &&
                watchedFolders(chain) == folders &&
                folders.keys.map { it.folderIdentity() } == identities

            val watch = if (refused) FolderWatch.Refused else openWaker(folders)
            if (watch == FolderWatch.Missing) {
                look(folders.keys)
                refused = awaitMissingFolder(folders.keys, openWaker, ::unmoved)
                continue
            }
            if (watch !is FolderWaker) {
                look(folders.keys)
                refused = true
                delay(pollEvery)
                continue
            }
            val lost = watch.use { waker ->
                // Looked up after the watch is set, so a change made while it was set up, a link pointed elsewhere
                // included, is not missed.
                look(folders.keys)
                var wake = Wake.Look
                while (wake in WATCH_GOES_ON && unmoved()) {
                    wake = waker.next()
                    when (wake) {
                        Wake.Quiet -> {}
                        Wake.Changed, Wake.Overflow -> look(folders.keys, told = true)
                        Wake.Look, Wake.FolderGone, Wake.Refused -> look(folders.keys)
                    }
                }
                wake
            }
            // A watch that fails because its folder went away, as on Windows, is no refusal.
            refused = lost == Wake.Refused && unmoved()
        }
    }
}

/**
 * Waits until the first of [folders] that is missing may be there, or until the path is not [unmoved], watching the
 * nearest folder above it that is there for the name on the way down; true when the system refused that watch.
 */
private suspend fun awaitMissingFolder(
    folders: Set<Path>,
    openWaker: (folders: Map<Path, Set<String>>) -> FolderWatch,
    unmoved: () -> Boolean,
): Boolean {
    var below = folders.firstOrNull { !it.isFolder() } ?: return false
    var above = below.above() ?: return true
    while (!above.isFolder()) {
        below = above
        above = above.above() ?: return true
    }
    val watched = above.canonicalPath().getOrNull() ?: above
    val identity = watched.folderIdentity()
    val watch = openWaker(mapOf(watched to setOf(below.name)))
    if (watch !is FolderWaker) return watch == FolderWatch.Refused
    return watch.use { waker ->
        // A folder made while the watch was set up, a link on the way pointed elsewhere, or the folder above swapped
        // tells this watch nothing, so each is looked for after every wake. A look names nothing on the way down.
        var wake = Wake.Quiet
        while (wake in AWAIT_GOES_ON && !below.isFolder() && unmoved() && watched.folderIdentity() == identity) {
            wake = waker.next()
        }
        wake == Wake.Refused
    }
}

private fun Path.isFolder(): Boolean = info().getOrNull()?.type == FileType.Directory

/** The folder this path is in, the working folder for a relative name, or null above a root, such as a drive's. */
private fun Path.above(): Path? = parent ?: Path(".").takeIf { !isAbsolute && it != this }

/**
 * The folders to watch for this path, each with the names in it to watch for: every folder its [chain] of links passes
 * through, its own folder first; a chain that goes round, null, is watched in its own folder alone. Each folder is
 * keyed by its [canonicalPath], so two spellings of one folder are one watch.
 */
private fun Path.watchedFolders(chain: List<Path>?): Map<Path, Set<String>> {
    val namesByFolder = LinkedHashMap<Path, MutableSet<String>>()
    for (stop in chain ?: listOf(this)) {
        val folder = stop.parent ?: Path(".")
        namesByFolder.getOrPut(folder.canonicalPath().getOrNull() ?: folder) { linkedSetOf() } += stop.name
    }
    return namesByFolder
}

/** What a `trusted` read judges of a folder the file is reached through: who may change it. */
private data class FolderTrust(
    val permissions: Permissions?,
    val ownedByMe: Boolean?,
)

/** This folder's [FolderTrust], or null when it cannot be looked up. */
private fun Path.trust(): FolderTrust? = info().getOrNull()?.let { FolderTrust(it.permissions, it.ownedByMe) }

/** Why a [FolderWaker] woke. */
internal enum class Wake {
    /** The system told of a change to a watched file. */
    Changed,

    /** Nothing said a watched file changed, but its folders may have, as with a folder's own mode. */
    Look,

    /** The system dropped events, so anything may have changed, a folder moved away too; the watch is set again. */
    Overflow,

    /** A wait slice passed with no news of a watched file; the watch goes on once its folders prove unmoved. */
    Quiet,

    /** The folder was deleted or moved away, which ends this watch. */
    FolderGone,

    /** The watch failed and the system may refuse another. */
    Refused,
}

private val WATCH_GOES_ON = setOf(Wake.Changed, Wake.Look, Wake.Quiet)

private val AWAIT_GOES_ON = setOf(Wake.Look, Wake.Quiet)

/** What [openFolderWaker] answers: the watch, or why there is none. */
internal sealed interface FolderWatch {
    /** The folder was not there to watch. */
    data object Missing : FolderWatch

    /** The system will not watch the folder. */
    data object Refused : FolderWatch
}

/** The system's watch on some folders, for some file names in each. */
internal interface FolderWaker : FolderWatch, AutoCloseable {
    /** Suspends until the system tells of a change to a watched file or folder, the watch ends, or a slice passes. */
    suspend fun next(): Wake
}

/**
 * One watch on all of [folders], each for the file names it maps to, or why the system has none: the first folder
 * missing or refused answers for all of them.
 */
internal expect fun openFolderWaker(folders: Map<Path, Set<String>>): FolderWatch
