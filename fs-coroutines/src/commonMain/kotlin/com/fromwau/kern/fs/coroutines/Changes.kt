package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileInfo
import com.fromwau.kern.fs.InternalKernApi
import com.fromwau.kern.fs.Permissions
import com.fromwau.kern.fs.canonicalPath
import com.fromwau.kern.fs.info
import com.fromwau.kern.fs.linkChain
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What [info] answers for this path now, and again each time that answer changes, for as long as you collect.
 *
 * ```kotlin
 * config.changes().collect { change ->
 *     change.onSuccess { reload() }.onError { println("$config: $it") }
 * }
 * ```
 *
 * The first answer comes when collection starts, not when you call this: a collector launched on the same thread before
 * code that does not suspend sees nothing until that code suspends, unless it is launched with
 * `start = CoroutineStart.UNDISPATCHED`, which collects the first answer before `launch` returns. After it, an answer
 * comes whenever it differs from the last one: another [FileInfo], an error where there was info or the reverse, or
 * another error. An error never ends the flow, so a deleted file is one [FileError.NotFound], and a file made there
 * again is info once more. A change to a watched folder's mode or owner gives the same answer again. Changes close
 * together may come as one answer.
 *
 * The operating system says when something in the file's folder changed: inotify on Linux, kqueue on Apple,
 * `FindFirstChangeNotificationW` on Windows, and `WatchService` on the JVM and Android. It looks every [pollEvery]
 * instead while the folder is missing, after the system refused to watch it, and on the JVM on macOS, whose
 * `WatchService` polls at a pace you cannot set. A folder deleted or moved away while watched is looked at every
 * [pollEvery] until it is back. Windows has no `changedAt`, so there a file replaced by one of the same size and
 * modification time gives no answer.
 *
 * A symlink is watched in the folder of every link in its chain and in the folder of the file the chain ends at, up
 * to the 40 links Linux follows, each folder once however the chain spells it. All of them share one system watch per
 * collector, such as one inotify instance. The chain is found again after each answer, so any link in it pointed
 * elsewhere gives an answer and moves the watch, and a folder the chain no longer passes through is no longer watched.
 *
 * A link to a folder on the way, such as `current -> releases/v1`, is followed too: the watched folders' real paths
 * are checked again after each change and each quarter second, and one pointed elsewhere moves the watch, with an
 * answer when that changes what [info] answers. When the system refuses to watch any of these folders, it looks every
 * [pollEvery] instead.
 * Where the system watch misses changes, as on a network file system, use [pollChanges].
 *
 * @param pollEvery how long to wait between lookups where it has no system watch.
 * @return a cold flow: each collector watches on its own, and what it watches with is released once it stops.
 * @throws IllegalArgumentException when [pollEvery] is not positive.
 */
public fun Path.changes(pollEvery: Duration = 1.seconds): Flow<Result<FileInfo, FileError>> =
    changes(pollEvery, ::openFolderWaker)

/**
 * What [info] answers for this path now, and again each time that answer changes, looking it up every [every].
 *
 * ```kotlin
 * share.pollChanges(every = 5.seconds).collect { change -> change.onSuccess { reload() } }
 * ```
 *
 * Answers come as [changes] gives them: the first when collection starts, then each that differs from the last, with
 * errors as answers that never end the flow. It never asks the operating system to watch, so pick it where that watch
 * misses changes. On network file systems such as NFS and SMB, inotify does not report a change made on another
 * machine. On a FUSE mount, it does not report a change the file system makes behind the mount. A change undone before
 * the next lookup gives no answer.
 *
 * @param every how long to wait between lookups.
 * @return a cold flow: each collector looks on its own.
 * @throws IllegalArgumentException when [every] is not positive.
 */
public fun Path.pollChanges(every: Duration = 1.seconds): Flow<Result<FileInfo, FileError>> {
    require(every.isPositive()) { "every must be positive, was $every" }
    // After a refusal no watch is asked for again, so every answer comes from a lookup.
    return changes(every) { FolderWatch.Refused }
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

        suspend fun look(folders: Set<Path>) {
            val trust = folders.mapNotNull { folder -> folder.trust()?.let { folder to it } }.toMap()
            val trustChanged = trust.any { (folder, current) -> seenTrust[folder].let { it != null && it != current } }
            val now = info()
            seenTrust = seenTrust + trust
            if (now != last || trustChanged) {
                last = now
                emit(now)
            }
        }

        var refused = false
        while (true) {
            val chain = linkChain()
            val folders = watchedFolders(chain)
            val watch = if (refused) FolderWatch.Refused else openWaker(folders)
            if (watch !is FolderWaker) {
                look(folders.keys)
                refused = watch == FolderWatch.Refused
                delay(pollEvery)
                continue
            }
            val lost = watch.use { waker ->
                // Looked up after the watch is set, so a change made while it was set up, a link pointed elsewhere
                // included, is not missed.
                look(folders.keys)
                var wake = Wake.Event
                // A link on the way to a watched folder, such as `current -> releases/v1`, tells its folder nothing
                // when pointed elsewhere, so the folders' real paths are checked after every wake.
                while (wake in WATCH_GOES_ON && linkChain() == chain && watchedFolders(chain) == folders) {
                    wake = waker.next()
                    if (wake != Wake.Quiet) look(folders.keys)
                }
                wake
            }
            refused = lost == Wake.Refused
        }
    }
}

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
    /** Something about the file may have changed. */
    Event,

    /** The system dropped events, so anything may have changed; the watch goes on. */
    Overflow,

    /** A wait slice passed with no news of a watched file; the watch goes on once its folders prove unmoved. */
    Quiet,

    /** The folder was deleted or moved away, which ends this watch. */
    FolderGone,

    /** The watch failed and the system may refuse another. */
    Refused,
}

private val WATCH_GOES_ON = setOf(Wake.Event, Wake.Overflow, Wake.Quiet)

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
