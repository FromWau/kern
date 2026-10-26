package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import kotlinx.io.files.Path

/**
 * Builds a new folder [name] in one step, so nobody ever sees it half built, as [createDirectoryAtomically] on a
 * path does. [build] fills a stage folder beside [name], `.<name>.<id>.dir`, through the writer it is handed, and
 * once it returns anything but an `Err`, the stage is renamed to [name]. The missing folders above [name] are made
 * first, as the writer makes folders, and stay whatever [build] answers.
 *
 * ```kotlin
 * data.createDirectoryAtomically("packages/$version") { stage ->
 *     stage.writeText("manifest.toml", manifest).onError { error("not written: $it") }
 * }.onError { println("not installed: $it") }
 * ```
 *
 * The stage's writer has the stage as its root and its boundary: a link the build makes or follows must stay inside
 * the stage, so `stage.switchSymlink("up", Path(".."))` is [FileError.Escapes], and so is a write through such a link
 * made some other way. A link to a folder inside the stage is followed. Once [name] is in place, that writer answers
 * [FileError.FolderReplaced].
 *
 * Its errors, and those of its children and of the [AppendFile]s, [FileLock]s and leftover scans they open, name
 * each path in the stage as it will be once placed, such as `packages/1.0/lib/up` below the root, and the placed
 * folder as an [FileError.Escapes]'s boundary, while its [Writer.root] is the stage. A [FileError.WriteFailed] or
 * [FileError.RestoreFailed] that names no spot of its own has the path in the stage as its [FailedAt.Temp].
 * [FileError.FolderReplaced] names the stage, its root. What you hand back to that writer keeps its path in the stage:
 * the [Leftover]s a scan finds or a [FileError.PendingBackup] carries, and an [AppendFile]'s or [FileLock]'s path.
 *
 * A build whose folder is replaced answers [FileError.FolderReplaced] and leaves its stage in that folder, wherever it
 * went; a [leftoversIn] scan of it finds a [Leftover.StagedFolder], which [discard] removes.
 *
 * @param build fills the stage. When it answers an `Err`, the stage is removed and nothing is put in place. What it
 *   throws is rethrown once the stage is removed.
 * @return `Ok` of what [build] returned, once the folder is in place, or of the `Err` it answered, with nothing
 *   placed; otherwise [FileError.FolderReplaced] when this writer's folder was replaced before or while [build] runs;
 *   [FileError.AlreadyExists] for anything already at [name], a link included, without calling [build], or for
 *   anything that appears there while [build] runs;
 *   [FileError.NameTooLong] naming [LongName.Stage] for a name over [MAX_STAGED_NAME_BYTES]; [FileError.LockFailed]
 *   when the claim could not be locked; or [FileError.WriteFailed] when the stage could not be made or renamed.
 */
public inline fun <T> Writer.createDirectoryAtomically(
    name: String,
    build: (stage: Writer) -> T,
): Result<T, FileError> {
    val stage = when (val made = makeStage(name)) {
        is Result.Success -> made.value
        is Result.Error -> return made
    }
    // Every way out removes the stage, a return out of build included; once it is renamed nothing is left there.
    try {
        val value = build(stage.writer)
        if (value is Result.Error<*>) return Ok(value)
        return placeStage(name, stage).map { value }
    } finally {
        removeStage(stage)
    }
}

/** A new stage beside [name] for [createDirectoryAtomically], claimed before it is made, or why there is none. */
@PublishedApi
internal fun Writer.makeStage(name: String): Result<WriterStage, FileError> = named(name, makes = true) { session ->
    val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
        .getOrElse { return@named Err(it) }
    val path = root / name
    spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) }
        ?.let { return@named Err(FileError.AlreadyExists(path, it)) }
    val stage = spot.level.pathOf(spot.name).besideWith(STAGE_SUFFIX)
    val claim = stage.stageClaim()
    path.tooLongToStage(claim, LongName.Stage)?.let { return@named it }
    val folderNames = name.substringBeforeLast('/', missingDelimiterValue = "")
    val beside = { entry: String -> if (folderNames.isEmpty()) entry else "$folderNames/$entry" }
    val unopened = { lockFile: Path, failure: Exception -> writeFailed(path, failure, at = FailedAt.Claim(lockFile)) }
    val held = lockIn(Spot(spot.level, claim.name), unopened).getOrElse { return@named Err(it) }
    var made = false
    val stageWriter = try {
        made = spot.level.makeFolder(stage.name, folderMode)
        val placed = shownPath(spot.level.pathOf(spot.name))
        spot.level.child(stage.name).use {
            val key = it.key()
            Writer(it.path, it.path, rootRule, rootKey = key, boundaryKey = key, placedBoundary = placed)
        }
    } catch (e: Exception) {
        if (made) spot.level.removeQuietly(stage.name, folder = true)
        spot.level.deleteQuietly(claim.name)
        held.close()
        return@named Err(writeFailed(path, e, at = FailedAt.Temp(stage)))
    }
    Ok(WriterStage(stageWriter, beside(stage.name), beside(claim.name), held))
}

/** Renames [stage] to [name], unless something is there by now. */
@PublishedApi
internal fun Writer.placeStage(
    name: String,
    stage: WriterStage,
): EmptyResult<FileError> = named(name) { session ->
    val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
        .getOrElse { return@named Err(it) }
    val path = root / name
    val alreadyThere = {
        FileError.AlreadyExists(path, spot.level.typeOrError(spot.name, path).getOrNull() ?: FileType.Other)
    }
    spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) }
        ?.let { return@named Err(alreadyThere()) }
    val staged = stage.writer.root.name
    val failure = when (val renamed = spot.level.renameNoReplace(staged, spot.name)) {
        NoReplaceRename.Renamed -> null
        NoReplaceRename.TargetTaken -> return@named Err(alreadyThere())
        NoReplaceRename.Unavailable -> try {
            spot.level.rename(staged, spot.name)
            null
        } catch (e: Exception) {
            e
        }
        is NoReplaceRename.Failed -> renamed.failure
    }
    if (failure != null) return@named Err(writeFailed(path, failure, at = FailedAt.Temp(stage.writer.root)))
    val _ = spot.level.sync()
    Ok(Unit)
}

/** Removes what is left of [stage] and its claim, then lets go of the claim. */
@PublishedApi
internal fun Writer.removeStage(stage: WriterStage) {
    // A stage that will not go is left for leftovers to find, and with its claim gone it can be discarded.
    val _ = deleteRecursively(stage.name)
    val _ = delete(stage.claimName)
    stage.claim.close()
}

/**
 * This error with each path in [stage] named as it will be once [stage] is placed at [placed]. A failure with no spot
 * of its own gets the path in the stage as [FailedAt.Temp]. A [FileError.FolderReplaced] keeps the stage, the writer's
 * root, and a [FileError.PendingBackup] its [Leftover], which a caller hands back to the stage's writer as it is; a
 * [FileError.RestoreFailed]'s backup is only shown, so it is named as placed.
 */
internal fun FileError.placedAs(
    stage: Path,
    placed: Path,
): FileError {
    val moved = { path: Path -> path.movedTo(stage, placed) }
    val spot = { at: FailedAt? -> at ?: FailedAt.Temp(path).takeIf { moved(path) != path } }
    return when (this) {
        is FileError.NotFound -> copy(path = moved(path))
        is FileError.AlreadyExists -> copy(path = moved(path))
        is FileError.Inaccessible -> copy(path = moved(path))
        is FileError.NotRegularFile -> copy(path = moved(path))
        is FileError.NotASymlink -> copy(path = moved(path))
        is FileError.NotADirectory -> copy(path = moved(path))
        is FileError.TooLarge -> copy(path = moved(path))
        is FileError.Refused -> copy(path = moved(path), asked = moved(asked))
        is FileError.AtomicRefused -> copy(path = moved(path))
        is FileError.NameTooLong -> copy(path = moved(path))
        is FileError.InvalidName -> copy(path = moved(path))
        is FileError.Escapes -> copy(path = moved(path), boundary = moved(boundary), resolved = moved(resolved))
        is FileError.WriteFailed -> copy(path = moved(path), at = spot(at))
        is FileError.RestoreFailed -> copy(path = moved(path), backup = backup?.let(moved), at = spot(at))
        is FileError.LockBusy -> copy(path = moved(path))
        is FileError.RunTempClaimed -> copy(path = moved(path))
        is FileError.LockFailed -> copy(path = moved(path))
        is FileError.LockLost -> copy(path = moved(path))
        is FileError.Closed -> copy(path = moved(path))
        is FileError.MirrorOverlap -> copy(path = moved(path), dst = moved(dst))
        is FileError.FolderReplaced -> this
        is FileError.InvalidRelativePath -> copy(folder = moved(folder))
        is FileError.PendingBackup -> copy(path = moved(path))
        is FileError.OutputClash -> copy(path = moved(path))
    }
}

/** This path with [from] at its start replaced by [to], or this path when it does not start with [from]. */
internal fun Path.movedTo(
    from: Path,
    to: Path,
): Path {
    val below = segments().belowOrNull(from.segments()) ?: return this
    return Path(to, *below.toTypedArray())
}

/** A stage [Writer.createDirectoryAtomically] builds in through [writer], at [name] beside its [claimName]. */
@PublishedApi
internal class WriterStage(
    val writer: Writer,
    val name: String,
    val claimName: String,
    val claim: FileLock,
)
