package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlin.time.Duration
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Builds a new folder at this path in one step, so nobody ever sees it half built. [build] fills a stage folder
 * beside this path, `.<name>.<id>.dir`, and once it returns anything but an `Err`, the stage is renamed to this path.
 *
 * ```kotlin
 * (output / "site").createDirectoryAtomically { stage ->
 *     (stage / "index.html").writeText(page).onError { failure ->
 *         error("${failure.path.relativeTo(stage) ?: failure.path} not written: $failure")
 *     }
 * }.onError { println("not built: $it") }
 * ```
 *
 * For a folder of your app, [Writer.createDirectoryAtomically] builds the same way and keeps every name below its root.
 *
 * Something already at this path, a folder, a file or a link, is [FileError.AlreadyExists] before anything is made,
 * and so is one that appears while [build] runs, which is never replaced. The JVM and Android have no rename that
 * refuses an existing folder, so there an empty folder made between the last check and the rename is replaced, as it
 * is on Linux and Apple on a file system or kernel without an exclusive rename. While [build] runs, a claim
 * `.<name>.<id>.lock` beside the stage is held, so [Writer.discard] leaves the stage of a running build alone; the
 * claim file is removed with the stage. A stage left behind by a process killed during [build] is what [leftovers]
 * finds as a [Leftover.StagedFolder].
 *
 * @param build fills the stage it is handed, whose path stays valid only while it runs, and returns the value this
 *   answers. When it answers an `Err`, the stage is removed and nothing is put in place, so
 *   `return@createDirectoryAtomically Err(e)` gives up the build, also inside a lambda that is not inline, such as
 *   `awaitLock`'s block. Any other answer, an `Ok` included, puts the folder in place. What it throws is rethrown once
 *   the stage is removed, and a `return` that leaves the function you call this from removes the stage too. An error
 *   from a call made inside it names the stage, `.<name>.<id>.dir` beside this path, since that is the path the call
 *   was given; `failure.path.relativeTo(stage)` names the entry within the folder being built.
 * @return `Ok` of what [build] returned, once the folder is in place, or of the `Err` it answered, with nothing
 *   placed; otherwise [FileError.AlreadyExists], [FileError.NameTooLong] naming [LongName.Stage] for a name over
 *   [MAX_STAGED_NAME_BYTES], whose claim would be longer than 255 bytes, [FileError.WriteFailed] when the stage or
 *   its claim could not be made or the stage renamed, or [FileError.LockFailed] when the claim could not be locked,
 *   and then nothing is left at this path.
 */
public inline fun <T> Path.createDirectoryAtomically(build: (stage: Path) -> T): Result<T, FileError> {
    val stage = when (val made = makeStage()) {
        is Result.Success -> made.value
        is Result.Error -> return made
    }
    // Every way out removes the stage, a return out of build included, and once it is renamed nothing is left there.
    try {
        val value = build(stage.path)
        if (value is Result.Error<*>) return Ok(value)
        return placeStage(stage.path).map { value }
    } finally {
        stage.remove()
    }
}

/** A stage folder at [path] that [createDirectoryAtomically] builds in, claimed by [claim] until [remove]. */
@PublishedApi
internal class Stage(
    val path: Path,
    private val claim: FileLock,
) {
    /** Removes the stage and what it holds, then the claim file, and lets go of the claim. */
    fun remove() {
        // A stage that will not go is left for leftovers to find, and with its claim gone it can be discarded.
        val _ = path.deleteRecursively()
        claim.deleteFileAndRelease()
    }
}

/**
 * A new, empty stage folder beside this path for [createDirectoryAtomically], claimed before it is made, or why there
 * can be none.
 */
@PublishedApi
internal fun Path.makeStage(): Result<Stage, FileError> {
    refusedPath()?.let { return it }
    existingOrUnknown()?.let { return it }
    val stage = besideWith(STAGE_SUFFIX)
    val claimFile = stage.stageClaim()
    tooLongToStage(claimFile, LongName.Stage)?.let { return it }
    val created = parent?.createDirectories()
    if (created is Result.Error) return created
    val unopened = { lockFile: Path, failure: Exception -> writeFailed(this, failure, at = FailedAt.Claim(lockFile)) }
    val claim = when (val taken = claimFile.acquireLock(Duration.ZERO, unopened = unopened)) {
        is Result.Success -> taken.value
        is Result.Error -> return taken
    }
    return try {
        val _ = makeFolder(stage, FOLDER_MODE)
        Ok(Stage(stage, claim))
    } catch (e: Exception) {
        claim.deleteFileAndRelease()
        Err(writeFailed(this, e, at = FailedAt.Temp(stage)))
    }
}

/** The claim file `.<name>.<id>.lock` beside this stage `.<name>.<id>.dir`, held while its build runs. */
internal fun Path.stageClaim(): Path = sibling(name.removeSuffix(STAGE_SUFFIX) + CLAIM_SUFFIX)

/** As long as [STAGED_LINK_SUFFIX], so [MAX_STAGED_NAME_BYTES] fits a stage's claim as it fits a staged link. */
private const val CLAIM_SUFFIX = ".lock"

/** Renames [stage] to this path, unless something is there by now; [afterCheck] runs between the two, for tests. */
@OptIn(InternalKernApi::class)
@PublishedApi
internal fun Path.placeStage(
    stage: Path,
    afterCheck: () -> Unit = {},
): EmptyResult<FileError> {
    // Where the platform has no rename that refuses a target, an empty folder there is replaced, so it is asked first.
    existingOrUnknown()?.let { return it }
    afterCheck()
    val failure = when (val renamed = renameNoReplace(stage, this)) {
        NoReplaceRename.Renamed -> null
        NoReplaceRename.TargetTaken -> return Err(alreadyExists())
        NoReplaceRename.Unavailable -> renameReplacing(stage, this)
        is NoReplaceRename.Failed -> renamed.failure
    }
    if (failure != null) {
        return Err(alreadyExistsOr { writeFailed(this, failure, at = FailedAt.Temp(stage)) })
    }
    val _ = syncDirectory(folderOrDot())
    return Ok(Unit)
}

private fun renameReplacing(
    from: Path,
    to: Path,
): Exception? = try {
    SystemFileSystem.atomicMove(from, to)
    null
} catch (e: Exception) {
    e
}

/** How [renameNoReplace] ended. */
internal sealed interface NoReplaceRename {
    data object Renamed : NoReplaceRename

    /** Something was at the target, so nothing moved. */
    data object TargetTaken : NoReplaceRename

    /** The platform, its kernel or the file system has no rename that refuses an existing target. */
    data object Unavailable : NoReplaceRename

    data class Failed(val failure: Exception) : NoReplaceRename
}

/** Renames [from] to [to] in one step that never replaces anything at [to]. */
internal expect fun renameNoReplace(
    from: Path,
    to: Path,
): NoReplaceRename

/** [FileError.AlreadyExists] when anything is at this path, the error looking it up, or null when nothing is. */
private fun Path.existingOrUnknown(): Err<FileError>? = when (val looked = fileType(followLinks = false)) {
    is Result.Success -> Err(FileError.AlreadyExists(this, looked.value))
    is Result.Error -> looked.takeUnless { it.error is FileError.NotFound }
}
