package com.fromwau.kern.fs

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
import kotlinx.io.buffered
import kotlinx.io.files.Path

/**
 * The copies writes left below the root, read as [leftoversIn] reads them, [depth] levels of folders below it too.
 * Each one in [LeftoverScan.found] can be dealt with through [discard] and [restore]. Those in
 * [LeftoverScan.throughLinks] lie wherever a symlink in the root leads, which can be outside it, and there
 * [discard] and [restore] take them only with `throughLinks`.
 *
 * @throws IllegalArgumentException when [depth] is negative.
 */
public fun Writer.leftovers(depth: Int = 0): Result<LeftoverScan, FileError> {
    requireDepth(depth)
    checkRoot().onError { return Err(it) }
    return root
        .leftoversIn(depth)
        .map { scan -> scan.copy(failed = scan.failed.map { ScanFailure(shownPath(it.path), shown(it.error)) }) }
        .mapError(::shown)
}

/**
 * Removes [leftover]'s copy, leaving the file it was for as it is. A [Leftover.StagedFolder] goes with all it
 * holds, then the claim `.<name>.<id>.lock` beside it; a build still running, in this process or another, holds
 * that claim, so its stage is [FileError.LockBusy] naming the claim and nothing is removed. Only a copy below the
 * root is removed, as every call of this writer keeps to it, judged by the real path of the folder it is in, so a
 * copy a scan named through a link to a folder below the root is one.
 *
 * @param throughLinks also removes a copy outside the root whose [Leftover.via], the link a scan found it through,
 *   lies below the root and leads to the folder the copy sits in. It is removed in that folder, which is refused as
 *   [FileError.Refused] when another user owns it or its group or everyone else may write it.
 * @return `Ok` once it is gone; [FileError.NotFound] for a copy already gone; [FileError.Escapes] for a copy
 *   outside the root, unless `throughLinks` takes it, with nothing removed; or what [delete] and [deleteRecursively]
 *   answer.
 */
public fun Writer.discard(
    leftover: Leftover,
    throughLinks: Boolean = false,
): EmptyResult<FileError> {
    if (throughLinks) whereItSits(leftover)?.let { found -> return found.flatMap { it.discard(leftover) } }
    val copy = namesBelowRoot(leftover.copy).getOrElse { return Err(it) }
    if (leftover !is Leftover.StagedFolder) return delete(copy)
    // Looked up first, since taking the claim makes its file, which a stage already gone has no use for.
    if (typeAt(copy).getOrElse { return Err(it) } == null) return Err(shown(FileError.NotFound(leftover.copy)))
    val claim = namesBelowRoot(leftover.copy.stageClaim()).getOrElse { return Err(it) }
    val held = tryLock(claim).getOrElse { return Err(it) }
    return try {
        deleteRecursively(copy)
    } finally {
        val _ = delete(claim)
        held.close()
    }
}

/**
 * Puts [backup]'s content back into its file, in the file itself, as a failed in-place write does, so its inode,
 * owner and other hard links stay, then removes the backup. A missing file is made with the backup's permissions
 * before any content goes in, and one that is there keeps its own. Only a backup and a file below the root are
 * touched, each judged as [discard] judges a copy.
 *
 * @param throughLinks also restores a backup outside the root found through a link below it, as [discard] takes such
 *   a copy, into the file beside it, so the link stays a link.
 * @return `Ok` once the file holds the backup's content and the backup is gone; [FileError.Escapes] for either
 *   outside the root, unless `throughLinks` takes it; [FileError.Refused] for the folder `throughLinks` found it in;
 *   [FileError.NotFound] or [FileError.Inaccessible] naming the backup when it cannot be read and
 *   [FileError.NotRegularFile] when it is no file, or naming the file when a link or anything else that is no file
 *   stands at its name, which is never written through, all before the file changes; [FileError.RestoreFailed]
 *   when writing the file failed after emptying it; or why the backup could not be removed once the file holds its
 *   content. On any failure the backup stays.
 */
public fun Writer.restore(
    backup: Leftover.Backup,
    throughLinks: Boolean = false,
): EmptyResult<FileError> {
    if (throughLinks) whereItSits(backup)?.let { found -> return found.flatMap { it.restore(backup) } }
    val copyName = namesBelowRoot(backup.copy).getOrElse { return Err(it) }
    val fileName = namesBelowRoot(backup.file).getOrElse { return Err(it) }
    return named(copyName) { session ->
        val copy = session.walk(copyName, LastName.Entry, makeFolders = false).getOrElse { return@named Err(it) }
        val file = session.walk(fileName, LastName.Entry, makeFolders = false).getOrElse { return@named Err(it) }
        when (val there = copy.level.typeOrError(copy.name, backup.copy).getOrElse { return@named Err(it) }) {
            FileType.Regular -> Unit
            null -> return@named Err(FileError.NotFound(backup.copy))
            else -> return@named Err(FileError.NotRegularFile(backup.copy, there))
        }
        val fileType = file.level.typeOrError(file.name, backup.file).getOrElse { return@named Err(it) }
        if (fileType != null && fileType != FileType.Regular) {
            return@named Err(FileError.NotRegularFile(backup.file, fileType))
        }
        val fileGone = fileType == null
        val opened = try {
            // The backup carries the file's mode from when it was made, which a file made afresh would not get.
            val permissions = if (fileGone) copy.level.permissionBits(copy.name) else null
            copy.level.openSource(copy.name) to permissions
        } catch (e: Exception) {
            return@named Err(e.lookUpError(backup.copy))
        }
        val (source, permissions) = opened
        val failed = source.buffered().use { read ->
            read.putBackInto { file.level.openSink(file.name, append = false, permissions) }
        }
        when (failed) {
            null -> Unit
            is PutBackFailure.Unread -> return@named Err(failed.failure.lookUpError(backup.copy))
            is PutBackFailure.NotOpened -> return@named Err(failed.failure.writeError(backup.file))
            is PutBackFailure.NotWritten -> return@named Err(
                FileError.RestoreFailed(backup.file, failed.failure.ioCause(), failed.failure, backup.copy),
            )
        }
        try {
            copy.level.remove(copy.name, folder = false)
            Ok(Unit)
        } catch (e: Exception) {
            Err(e.removalError(backup.copy))
        }
    }
}

/**
 * A writer on the folder [leftover]'s copy really sits in, that folder refused as [RootRule.Trusted] refuses a root,
 * for a copy outside the root found through a link below it; [FileError.Escapes] when that link is not below the
 * root, or does not lead to that folder; or null for a copy below the root or one found without a link, which the
 * writer itself judges.
 */
private fun Writer.whereItSits(leftover: Leftover): Result<Writer, FileError>? {
    if (namesBelowRoot(leftover.copy) is Result.Success) return null
    val via = leftover.via ?: return null
    namesBelowRoot(via).onError { return Err(it) }
    val folder = leftover.copy.parent ?: return null
    val realFolder = folder.canonicalPath().getOrNull()
    if (realFolder == null || via.linkedFolder() != realFolder) {
        return Err(shown(FileError.Escapes(leftover.copy, boundary, realFolder ?: folder)))
    }
    return folder.newWriter(RootRule.Trusted, create = false, asked = leftover.copy)
}

/**
 * The real folder the symlink at this path leads to: its target's real path for a link to a folder, and the real
 * folder its target sits in for a link to a file or to nothing, where a write through it leaves its copies. Null when
 * this is no link, or its chain of links goes round.
 */
private fun Path.linkedFolder(): Path? {
    if (!isSymlink(this)) return null
    val end = linkChainEnd() ?: return null
    if (end.fileType() == Ok(FileType.Directory)) return end.realPath().getOrNull()
    return end.parent?.canonicalPath()?.getOrNull()
}

/**
 * Removes everything below the root, each entry as [deleteRecursively] removes it, then the root itself, now empty;
 * for kern's own folders that live only as long as their work, such as a run's temp folder. A root already gone is
 * `Ok`, while another folder or a symlink standing in its place is [FileError.FolderReplaced] and stays.
 */
internal fun Writer.clearAndRemoveRoot(): EmptyResult<FileError> {
    clearRoot().onError { return if (root.isVacant()) Ok(Unit) else Err(it) }
    return root.delete().orIfNotFound { }
}
