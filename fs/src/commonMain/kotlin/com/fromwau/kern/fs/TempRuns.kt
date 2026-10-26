package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.mapError
import com.fromwau.kern.result.onError
import kotlin.time.Duration
import kotlinx.io.files.Path

/**
 * Runs [block] with this run's [AppDirs.temp] created and claimed, so [staleTempRuns] in another process knows
 * the run is alive, and clears the folder when [block] ends. Wrap the work that uses the folder, usually the whole
 * of `main`:
 *
 * ```kotlin
 * dirs.withRunTemp { temp -> runApp(temp) }.onError { println("no run temp: $it") }
 * ```
 *
 * [block] is handed a [privateWriter] on the folder, so everything it makes there is `600` or `700` and stays below
 * it. [block] may call suspending functions when the caller can.
 *
 * The claim is a [withLock] on `.<run id>.lock` beside the folder, taken with no wait before the folder is created
 * and held until it has been cleared. There is one run temp per [AppDirs]: a second [withRunTemp] on it while the
 * first runs, in this process too, is [FileError.RunTempClaimed] at once, naming this process as the holder, so
 * claim it once and pass the folder down. Another process can only hold the same run id when a fixed id is passed
 * to [BaseDirs.forApp].
 *
 * The folder is created `700`, and one already there is made so, before [block] runs, so only its owner may list,
 * enter or change it, on Linux, Apple, Android and the JVM on a file system with POSIX permissions; Windows leaves it
 * to the access rules of the folder it is made in.
 *
 * The folder holding the runs' folders is made `755` when missing, narrower as the umask says; where the file system
 * keeps POSIX permissions it must be yours, with no one else allowed to write it. It may not be a symlink itself,
 * while links above it are followed.
 *
 * A symlink at the run temp's own path is not followed, also one put there while [block] runs, but for what [Writer]
 * says of the JVM, Android, macOS and Windows.
 *
 * Whether [block] returns, throws or is cancelled, the folder is then cleared through that writer, each entry as
 * [Writer.deleteRecursively] removes it, then removed, and the lock file is deleted while the claim is still held.
 * A process that is killed clears nothing, and [staleTempRuns] finds its folder later.
 *
 * @return what [block] returned, or why the folder could not be created, claimed or cleared. A folder holding the runs'
 *   folders that another user owns or its group or everyone else may write is [FileError.Refused] naming it, and a
 *   symlink there is [FileError.NotADirectory] naming it, with nothing made in it either way. A run temp already
 *   claimed is [FileError.RunTempClaimed] naming the folder by its real path, with nothing created or cleared. A
 *   symlink at the folder's path is [FileError.NotADirectory], and one put there while [block] runs, one leading
 *   nowhere too, is [FileError.FolderReplaced], what it leads to left alone either way. A claim file that cannot be
 *   created is [FileError.WriteFailed] naming the folder that holds the runs' folders, with the claim file as its
 *   [FailedAt.Claim], and one that is there but will not open is [FileError.LockFailed] naming that claim file; a
 *   folder this user cannot make `700` is [FileError.WriteFailed] naming it, and one that another user owns or others
 *   may write or read once it is made so is [FileError.Refused], as [privateWriter] answers. What [block] throws is not
 *   caught, and when [block] throws, a failure to clear the folder afterwards is lost.
 */
public inline fun <T> AppDirs.withRunTemp(block: (temp: Writer) -> T): Result<T, FileError> {
    val claim = when (val claimed = claimRunTemp()) {
        is Result.Success -> claimed.value
        is Result.Error -> return claimed
    }
    // A return out of block skips a catch, so only a finally sees every way out. It reads no local flag: on the JVM,
    // such a finally inlined into another module misreads a local set in the try when block returns out of it.
    try {
        val value = block(claim.writer)
        return claim.release().map { value }
    } finally {
        claim.releaseUnlessReleased()
    }
}

/** This run's temp folder, created and claimed as [withRunTemp] describes, with [writer] on it, until [release]. */
@PublishedApi
internal class RunTempClaim(
    val writer: Writer,
    private val lock: FileLock,
) {
    private var released = false

    /** Clears the folder, then deletes the claim file and lets go of the claim; answers how clearing went. */
    fun release(): EmptyResult<FileError> {
        released = true
        try {
            return writer.clearAndRemoveRoot()
        } finally {
            lock.deleteFileAndRelease()
        }
    }

    /** [release], unless that already ran, for a way out of [withRunTemp] whose outcome nobody reads. */
    fun releaseUnlessReleased() {
        if (!released) {
            val _ = release()
        }
    }
}

/** Claims this run's temp folder and creates it, as [withRunTemp] describes, or answers why that failed. */
@PublishedApi
internal fun AppDirs.claimRunTemp(): Result<RunTempClaim, FileError> {
    temp.runs.notALink().onError { return Err(it) }
    makeFolders(temp.runs, SHARED_FOLDER_MODE).onError { return Err(it) }
    val run = temp.runs.runsWriter().getOrElse { return Err(it) }.runPath(temp)
    val lock = when (val taken = run.runLock().acquireLock(Duration.ZERO, unopened = run.unclaimable())) {
        is Result.Success -> taken.value
        is Result.Error -> {
            val busy = taken.error as? FileError.LockBusy ?: return taken
            return Err(FileError.RunTempClaimed(run, busy.holderPid))
        }
    }
    val writer = run.createPrivateDirectory().flatMap { writerAt(run, RootRule.Private, asked = temp) }
    if (writer is Result.Error) lock.deleteFileAndRelease()
    return writer.map { RunTempClaim(it, lock) }
}

/**
 * The temp folders of this app's other runs that no live run has claimed, which is what a run that crashed or
 * was killed leaves behind, which [clearStaleTempRuns] deletes. Only folders named like a run id, as
 * [BaseDirs.forApp] names them, are runs: anything else beside them, a symlink too, is never listed or followed. This
 * run's own folder is never listed either.
 *
 * It only tells live runs apart when every run of the app claims its folder with [withRunTemp]: a folder whose
 * run never did counts as stale. A run takes its claim before its folder exists, so one starting at that moment
 * is never listed, and a folder gone by the time its claim is taken, as one whose run just ended, is in neither list.
 * Asking leaves a `.<run id>.lock` beside each folder it looks at.
 *
 * @return the stale folders and the failures of those that could not be told apart, in name order, each folder named by
 *   its real path, links above it resolved. Every folder is tried: one that cannot be looked up, or whose claim file is
 *   there but will not open, is in [StaleRuns.failed], the latter as [FileError.LockFailed] naming that claim file. The
 *   error is only for what stops every folder: the folder that holds the runs' folders cannot be listed, or a claim
 *   file cannot be created in it, such as when this user may not write it, which is [FileError.WriteFailed] naming that
 *   folder, with the claim file as its [FailedAt.Claim]. That folder is [FileError.Refused] when another user owns it
 *   or its group or everyone else may write it, and [FileError.NotADirectory] when it is a symlink, with nothing made
 *   in it either way.
 */
public fun AppDirs.staleTempRuns(): Result<StaleRuns, FileError> = staleTempRuns(beforeClaim = {})

/** [staleTempRuns], running [beforeClaim] on each run folder between the listing and its claim. */
internal fun AppDirs.staleTempRuns(beforeClaim: (Path) -> Unit): Result<StaleRuns, FileError> =
    collectStaleRuns(beforeClaim) { Ok(Unit) }

/** What [staleTempRuns] found: the run folders that are [stale], and why each one it could not check [failed]. */
public data class StaleRuns(
    val stale: List<Path>,
    val failed: List<StaleFailure>,
)

/**
 * A run [folder] that [staleTempRuns] could not check or [clearStaleTempRuns] could not clear, and the [error] that
 * stopped it, which names the entry that would not go: [folder] itself, something inside it, or its claim file.
 */
public data class StaleFailure(
    val folder: Path,
    val error: FileError,
)

/**
 * Deletes what [staleTempRuns] would list: each folder named like a run id of this app's other runs that no live run
 * has claimed, and the `.<run id>.lock` beside it. Anything else beside them, a symlink too, is left alone and never
 * followed.
 *
 * Each folder is claimed with no wait, as [withRunTemp] claims its own, cleared with [deleteRecursively], and its
 * lock file deleted while the claim is still held; a claim that is busy is a live run, whose folder is passed
 * over. A folder gone by the time its claim is taken, as one whose run just ended, is in neither list. A lock file
 * with no folder beside it is left alone: a run starting now takes its claim before it creates its folder, so that
 * lock file may be in use.
 *
 * ```kotlin
 * dirs.clearStaleTempRuns().fold(
 *     onSuccess = { cleanup -> cleanup.failed.forEach { println("left behind: $it") } },
 *     onError = { println("not cleared: $it") },
 * )
 * ```
 *
 * @return the folders cleared and the failures of those that could not be, in name order, each folder named by its real
 *   path. Every folder is tried: one that cannot be cleared is in [StaleCleanup.failed], a [StaleFailure] naming it,
 *   with what [deleteRecursively] or deleting its lock file returned, naming the entry that would not go, or with
 *   [FileError.LockFailed] naming a claim file that is there but will not open. A folder another user owns or others
 *   may write fails as [FileError.Refused], while one others may only read is cleared. A folder that fails stays, so
 *   every later call fails on it again until it can be cleared. The error is only for what stops every folder: the
 *   folder that holds the runs' folders cannot be listed, or a claim file cannot be created in it, which is
 *   [FileError.WriteFailed] naming that folder, with the claim file as its [FailedAt.Claim]. That folder is
 *   [FileError.Refused] when another user owns it or its group or everyone else may write it, and
 *   [FileError.NotADirectory] when it is a symlink, with nothing made or cleared in it either way. A run folder swapped
 *   for a symlink once listed, one leading nowhere too, fails as [FileError.FolderReplaced], and the link and what it
 *   leads to are left alone.
 */
public fun AppDirs.clearStaleTempRuns(): Result<StaleCleanup, FileError> = clearStaleTempRuns(beforeClaim = {})

/** [clearStaleTempRuns], running [beforeClaim] on each run folder between the listing and its claim. */
internal fun AppDirs.clearStaleTempRuns(beforeClaim: (Path) -> Unit): Result<StaleCleanup, FileError> =
    collectStaleRuns(beforeClaim) { realRun -> realRun.clearStaleRun() }
        .map { StaleCleanup(cleared = it.stale, failed = it.failed) }

/**
 * Clears this stale run folder, a real path but for its own name, which is never followed, through a private [Writer]
 * on it that takes one others may read and makes nothing, then the claim file beside it.
 */
internal fun Path.clearStaleRun(): EmptyResult<FileError> = writerAt(this, RootRule.Trusted)
    .mapError { if (it is FileError.FolderReplaced && isVacant()) FileError.NotFound(this) else it }
    .flatMap { it.clearAndRemoveRoot() }
    .flatMap { runLock().delete().orIfNotFound { } }

/** What [clearStaleTempRuns] did: the run folders it [cleared], and why each one it could not clear [failed]. */
public data class StaleCleanup(
    val cleared: List<Path>,
    val failed: List<StaleFailure>,
)

/** What came of one folder [staleRuns] looked at. */
private sealed interface StaleRun {
    /** Claimed, and its work done while holding the claim. */
    data class Claimed(val run: Path) : StaleRun

    /** This folder alone failed. */
    data class Failed(val failure: StaleFailure) : StaleRun

    /** Stopped for [error], which no folder after this one would escape either; the last outcome. */
    data class Halted(val error: FileError) : StaleRun
}

/**
 * Runs [whileClaimed] on each stale run, as [staleRuns] does, into the runs it was done for and the failures of the
 * rest, or the error that stopped every folder.
 */
private fun AppDirs.collectStaleRuns(
    beforeClaim: (Path) -> Unit,
    whileClaimed: (Path) -> EmptyResult<FileError>,
): Result<StaleRuns, FileError> {
    val done = mutableListOf<Path>()
    val failed = mutableListOf<StaleFailure>()
    for (outcome in staleRuns(beforeClaim, whileClaimed)) {
        when (outcome) {
            is StaleRun.Claimed -> done += outcome.run
            is StaleRun.Failed -> failed += outcome.failure
            is StaleRun.Halted -> return Err(outcome.error)
        }
    }
    return Ok(StaleRuns(done, failed))
}

/**
 * Runs [whileClaimed] on each folder named like a run id of this app's other runs whose claim it can take, holding
 * that claim, as the sequence is iterated, each run named by its path in the real folder holding the runs. A folder
 * whose claim is busy belongs to a live run and is passed over, and so is one gone once its claim is taken.
 */
private fun AppDirs.staleRuns(
    beforeClaim: (Path) -> Unit,
    whileClaimed: (Path) -> EmptyResult<FileError>,
): Sequence<StaleRun> = sequence {
    var claimFileUncreatable = false
    val unopened = temp.unclaimable(onUncreatable = { claimFileUncreatable = true })
    val runs = temp.runs
    runs.notALink().onError {
        yield(StaleRun.Halted(it))
        return@sequence
    }
    val runsWriter = when (val judged = runs.runsWriter()) {
        is Result.Success -> judged.value
        is Result.Error -> {
            if (judged.error !is FileError.NotFound) yield(StaleRun.Halted(judged.error))
            return@sequence
        }
    }
    val entries = when (val listed = runsWriter.root.list()) {
        is Result.Success -> listed.value
        is Result.Error -> {
            if (listed.error !is FileError.NotFound) yield(StaleRun.Halted(listed.error))
            return@sequence
        }
    }
    for (run in entries) {
        // A run id is spelled as BaseDirs.forApp spells one.
        if (run.name == temp.name || run.name.canonicalUuidOrNull() == null) continue
        // Never followed: a link named like a run would hand its target's contents to the cleanup.
        when (val type = run.fileType(followLinks = false)) {
            is Result.Success -> if (type.value != FileType.Directory) continue
            is Result.Error -> {
                // Gone since the listing, as a folder another cleanup just removed is.
                if (type.error !is FileError.NotFound) yield(StaleRun.Failed(StaleFailure(run, type.error)))
                continue
            }
        }
        beforeClaim(run)
        val claimed = run.runLock().acquireLock(Duration.ZERO, unopened = unopened).map { held ->
            held.use {
                // A run that ended since the listing took its folder and lock file along, so this lock file is new.
                if (run.isVacant()) {
                    val _ = held.path.delete()
                    Claim.Gone
                } else {
                    when (val done = whileClaimed(run)) {
                        is Result.Success -> Claim.Done
                        is Result.Error -> Claim.Failed(done.error)
                    }
                }
            }
        }
        when (claimed) {
            is Result.Success -> when (val done = claimed.value) {
                Claim.Gone -> Unit
                Claim.Done -> yield(StaleRun.Claimed(run))
                is Claim.Failed -> yield(StaleRun.Failed(StaleFailure(run, done.error)))
            }
            is Result.Error -> when {
                claimed.error is FileError.LockBusy -> Unit
                // The folder holding the runs refuses every claim file, not only this one.
                claimFileUncreatable -> {
                    yield(StaleRun.Halted(claimed.error))
                    return@sequence
                }
                else -> yield(StaleRun.Failed(StaleFailure(run, claimed.error)))
            }
        }
    }
}

/** What holding one stale run's claim came to. */
private sealed interface Claim {
    /** The run ended since the listing, taking its folder along. */
    data object Gone : Claim

    data object Done : Claim

    data class Failed(val error: FileError) : Claim
}

/**
 * A run claim beside this temp folder that would not open: a write into the folder holding it when the claim file
 * could not be created, after [onUncreatable] runs, and [FileError.LockFailed] naming the claim file when it is there
 * but would not open.
 */
private fun Path.unclaimable(
    onUncreatable: () -> Unit = {},
): (lockFile: Path, failure: Exception) -> FileError = { lockFile, failure ->
    if (lockFile.isGone()) {
        onUncreatable()
        writeFailed(parent ?: lockFile, failure, at = FailedAt.Claim(lockFile))
    } else {
        lockFailed(lockFile, failure)
    }
}

/** The folder holding this run's temp folder and those of the app's other runs. */
private val Path.runs: Path get() = parent ?: Path(".")

/**
 * A writer on this folder holding the runs' folders, refused as [RootRule.Trusted] refuses a root: [FileError.Refused]
 * when another user owns it or its group or everyone else may write it.
 */
private fun Path.runsWriter(): Result<Writer, FileError> = newWriter(RootRule.Trusted, create = false)

/**
 * [FileError.NotADirectory] for a symlink at this path itself, which a writer on it would follow, naming the link by
 * the real path of its folder and its own name.
 */
private fun Path.notALink(): EmptyResult<FileError> =
    if (isSymlink(this)) Err(FileError.NotADirectory(folderOrDot().oneName() / name, FileType.Symlink)) else Ok(Unit)

/** [run] in the real folder this writer is on, its own name never resolved, so a link at it is not followed. */
private fun Writer.runPath(run: Path): Path = Path(root, run.name)

/** The file a run holds the lock on for as long as it uses this temp folder, beside the folder itself. */
private fun Path.runLock(): Path = sibling(".$name.lock")

/**
 * Creates this folder, and its missing parents as [createDirectories] does, as one only its owner may use, and makes
 * one already there the same, before anything goes in. [restrict] is what makes it so. A folder this call created
 * that cannot be given that mode is removed again; one that was already there, as under a fixed run id, stays.
 */
internal fun Path.createPrivateDirectory(restrict: (Path) -> Unit = ::restrictToOwner): EmptyResult<FileError> {
    val parents = parent?.createDirectories()
    if (parents is Result.Error) return parents
    val created = try {
        makeFolder(this, OWNER_ONLY_FOLDER)
    } catch (e: Exception) {
        return Err(notAFolderHere() ?: writeFailed(this, e))
    }
    // A link to a folder counts as one already there for makeFolder, and restrict would change what it leads to.
    if (!created) notAFolderHere()?.let { return Err(it) }
    return try {
        // Set as well as made with, since the umask can narrow a new folder's mode and one already there has its own.
        restrict(this)
        Ok(Unit)
    } catch (e: Exception) {
        if (created) {
            val _ = deleteRecursively()
        }
        Err(writeFailed(this, e))
    }
}

/** [FileError.NotADirectory] when anything but a folder, a symlink included, is at this path itself; or null. */
private fun Path.notAFolderHere(): FileError? {
    val type = fileType(followLinks = false).getOrNull() ?: return null
    return if (type == FileType.Directory) null else FileError.NotADirectory(this, type)
}
