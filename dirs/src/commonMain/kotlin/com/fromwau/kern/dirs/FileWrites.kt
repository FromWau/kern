package com.fromwau.kern.dirs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.map
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.uuid.Uuid

/**
 * Replaces this file's content with [text] as UTF-8 by writing into the file itself, so its permissions, owner
 * and links stay as they are. The old content is first copied to a backup beside the file, `.<name>.<id>.bak`. A
 * failed write moves the backup back, which restores the content but not the metadata; a successful write then
 * tries to delete it, and a backup that cannot be deleted stays. When undoing a failed write fails as well, the
 * file is left damaged and the result is [FileError.RestoreFailed].
 *
 * The backup needs write permission on the folder, and a file that cannot be read or written is refused before
 * anything changes. Anything at the path that is not a regular file is [FileError.NotRegularFile], a directory,
 * a FIFO and a device alike: opening a FIFO waits on a reader that may never come, and putting a backup back
 * over a device node would replace the node with a regular file. Missing parent directories are created, but
 * not behind a symlink whose target is missing: that target cannot be resolved, so the write fails on the link
 * rather than creating the folders the target needs.
 *
 * This is not atomic, and only one write may run at a time, whether from other threads or other processes.
 * Readers can see the file half-written, and two writes at once can leave a mix of both or undo each other. A
 * process killed mid-write leaves the file half-written with the backup beside it, or with no backup when the
 * write was creating the file.
 *
 * Where a platform has permissions the backup carries the file's own, so a copy of a file only you may read is
 * only readable by you, and a restore leaves the file holding them. On Windows native, where kotlinx-io cannot
 * follow links, a restore replaces a symlink with a regular file, and undoing a failed first write through a
 * symlink whose target is missing can remove the link instead of the new file.
 */
public fun Path.writeText(text: String): EmptyResult<FileError> = writeBytes(text.encodeToByteArray())

/** [writeText] for raw bytes. */
public fun Path.writeBytes(bytes: ByteArray): EmptyResult<FileError> {
    val target = writeTarget()
    val created = target.parent?.createDirectories()
    if (created is Result.Error) return created
    val existing = when (val found = metadata()) {
        is Result.Success -> found.value
        is Result.Error -> if (found.error is FileError.NotFound) null else return found
    }
    if (existing != null && !existing.isRegularFile) return Err(FileError.NotRegularFile(this, existing.type))

    var backup: Path? = null
    val sink = try {
        if (existing != null) {
            backup = target.backupBeside()
            copyFile(from = target, to = backup)
        }
        // Opening empties the file, so an open that fails has changed nothing and needs no restore.
        SystemFileSystem.sink(target)
    } catch (e: Exception) {
        backup?.deleteQuietly()
        return Err(FileError.WriteFailed(this, e.reason))
    }

    return try {
        sink.buffered().use { it.write(bytes) }
        backup?.deleteQuietly()
        Ok(Unit)
    } catch (e: Exception) {
        val undo = backup?.let { Undo.Restore(it) } ?: Undo.Remove
        when (val undone = undoFailure(undo, over = target)) {
            null -> Err(FileError.WriteFailed(this, e.reason))
            // Only a backup proved gone goes unnamed: pointing a recovery at a path that holds nothing is
            // worse than saying there is none, and a lookup that cannot answer has proved nothing.
            else -> Err(FileError.RestoreFailed(this, undone, backup?.takeUnless { it.isGone() }))
        }
    }
}

/**
 * Removes this file, symlink or empty directory. A symlink goes as a link, so what it points at stays. A
 * directory that still holds entries is [FileError.WriteFailed]; [deleteRecursively] clears a tree.
 *
 * kotlinx-io looks a path up before removing it, following symlinks, so a symlink whose target is missing reads
 * as [FileError.NotFound] and stays where it is.
 */
public fun Path.delete(): EmptyResult<FileError> = try {
    SystemFileSystem.delete(this, mustExist = true)
    Ok(Unit)
} catch (e: Exception) {
    metadata().fold(
        // It is there, so the removal itself is what failed: a directory with entries, or a read-only parent.
        onSuccess = { whyNotDeleted(this, e)?.let { Err(FileError.WriteFailed(this, it)) } ?: Ok(Unit) },
        onError = { Err(it) },
    )
}

/**
 * Why removing [path] failed, given the [failure] kotlinx-io raised, or null when asking removed it after all.
 * The JVM's own delete reports no reason, so there the removal is tried once more in a way that says why.
 */
internal expect fun whyNotDeleted(path: Path, failure: Exception): String?

/**
 * Why creating the folder [path] failed when nothing was thrown, or null when asking again created it. The JVM's
 * own creation reports no reason, so there it is tried once more in a way that says why.
 */
internal expect fun whyNotCreated(path: Path): String?

/**
 * Moves this file or folder to [target] in one step, so nothing ever sees it half moved, and the old path is
 * gone once this succeeds. A symlink moves as itself rather than as what it points at.
 *
 * Both paths must be on one file system: a move across two is [FileError.WriteFailed], so copy and delete
 * instead. A file already at [target] is replaced on the JVM, Android, Linux and Apple; whether Windows native
 * replaces it has not been verified. A folder replaces only an empty folder, and only where the OS allows it.
 * Nothing at this path is [FileError.NotFound]; every other failure is [FileError.WriteFailed] naming
 * [target], since the source is evidently there.
 */
public fun Path.moveTo(target: Path): EmptyResult<FileError> = try {
    SystemFileSystem.atomicMove(this, target)
    Ok(Unit)
} catch (e: Exception) {
    metadata().fold(
        onSuccess = { Err(FileError.WriteFailed(target, e.reason)) },
        onError = { Err(it) },
    )
}

/**
 * Removes this path and, when it is a directory, everything under it. Every entry goes as it is, so a symlink
 * is removed as a link and its target is untouched. The first failure stops the walk and is returned, which can
 * leave part of the tree behind.
 *
 * A symlink whose target is missing cannot be removed through kotlinx-io, so a tree holding one ends in
 * [FileError.WriteFailed] on the directory that still contains it.
 */
public fun Path.deleteRecursively(): EmptyResult<FileError> {
    // Removing first keeps a symlink a link: the tree below is only entered for a directory that resists.
    val removed = delete()
    if (removed !is Result.Error) return removed

    val children = when (val listed = list()) {
        is Result.Success -> listed.value
        // Not a directory after all, so the removal's own failure is the one that explains it.
        is Result.Error -> return removed
    }
    for (child in children) {
        val childRemoved = child.deleteRecursively()
        if (childRemoved is Result.Error) return childRemoved
    }
    return delete()
}

/**
 * Creates this directory and any missing parents, like `mkdir -p`. Succeeds when it already exists as a
 * directory or a symlink to one, also when another process creates it at the same time. A file at the path, or
 * in place of one of its parents, is [FileError.NotADirectory] naming that file, and a path that may not be
 * looked up at all is [FileError.Inaccessible] naming that path.
 */
public fun Path.createDirectories(): EmptyResult<FileError> {
    val missing = mutableListOf<Path>()
    // Only the levels below the deepest existing directory: on Windows, an ancestor can be a UNC server name.
    for (level in generateSequence(this) { it.parent }) {
        val held = when (val here = level.directoryHere()) {
            is Result.Success -> here.value
            is Result.Error -> return here
        }
        if (held) break
        missing += level
    }

    // One level at a time: native kotlinx-io throws when another process creates a level first.
    for (level in missing.asReversed()) {
        val failure = try {
            SystemFileSystem.createDirectories(level)
            null
        } catch (e: Exception) {
            e
        }
        // Judged by what is there afterwards, since the JVM's mkdirs can also fail without throwing.
        val held = when (val here = level.directoryHere()) {
            is Result.Success -> here.value
            is Result.Error -> return here
        }
        if (!held) {
            // Null when asking again created the folder after all, which leaves nothing to report.
            val reason = failure?.reason ?: whyNotCreated(level) ?: continue
            return Err(FileError.WriteFailed(level, reason))
        }
    }
    return Ok(Unit)
}

/**
 * Whether a directory is already at this path. A path nothing is at answers false, and one holding something
 * else is [FileError.NotADirectory]. One that could not be looked up answers neither, since treating it as
 * missing would ask for a level that may well be there.
 */
private fun Path.directoryHere(): Result<Boolean, FileError> = metadata().fold(
    onSuccess = { if (it.isDirectory) Ok(true) else Err(FileError.NotADirectory(this, it.type)) },
    onError = { if (it is FileError.NotFound) Ok(false) else Err(it) },
)

/** What undoing a failed write has to do, so that having neither a backup nor a new file cannot be asked for. */
internal sealed interface Undo {
    /** The file was there, so [backup] holds what it held and goes back over it. */
    data class Restore(val backup: Path) : Undo

    /** The file was new, so whatever the write left at the path is removed. */
    data object Remove : Undo
}

/**
 * Undoes a write that failed after emptying [over], as [undo] says. Answers why that failed, or null when it
 * worked.
 *
 * Moving the backup back needs write permission on the folder, which the write that just failed may have
 * lost, so this fails for reasons of its own and they are not the ones the write failed for.
 */
internal fun undoFailure(
    undo: Undo,
    over: Path,
): String? = try {
    when (undo) {
        is Undo.Restore -> SystemFileSystem.atomicMove(undo.backup, over)
        // Through a dangling symlink the new file sits at the link's target, which is what gets removed. A
        // path that cannot be looked up is tried anyway, so its own failure is reported instead of ignored.
        Undo.Remove -> if (!over.isGone()) SystemFileSystem.delete(SystemFileSystem.resolve(over))
    }
    null
} catch (e: Exception) {
    e.reason
}

private fun copyFile(
    from: Path,
    to: Path,
) {
    // A new file gets the writing process's default permissions, which can be wider than the ones on the file
    // being copied, so it is made empty and given the file's own before any content goes into it.
    SystemFileSystem.sink(to).close()
    copyPermissions(from = from, to = to)
    SystemFileSystem
        .source(from)
        .buffered()
        .use { source ->
            SystemFileSystem
                .sink(to)
                .buffered()
                .use { it.transferFrom(source) }
        }
}

/**
 * The file a write acts on: what a symlink points at, so the backup and a restore land beside it and the link
 * survives. Unresolvable paths are used as given.
 */
private fun Path.writeTarget(): Path = try {
    if (SystemFileSystem.exists(this)) SystemFileSystem.resolve(this) else this
} catch (_: Exception) {
    this
}

/** A backup name beside this file, carrying the id of the write that made it. */
private fun Path.backupBeside(): Path {
    val sibling = ".$name.${Uuid.random()}$BACKUP_SUFFIX"
    return parent?.let { Path(it, sibling) } ?: Path(sibling)
}

/**
 * The id of the write that left this name beside the file called [of], or null when no write left it.
 *
 * The id is what tells a backup from a file someone else put there. It is unique, so a later write never
 * overwrites one a killed write left; and it is unguessable, so `.<name>.before-my-edit.bak` beside a config
 * is somebody's own copy rather than evidence that a write died.
 */
private fun String.backupIdOf(of: String): Uuid? {
    val prefix = ".$of."
    // `.<name>.bak` carries no id and ends where the prefix does, so the two would overlap and the id read
    // backwards. The same guard is what keeps a name shorter than both from being cut at all.
    val end = length - BACKUP_SUFFIX.length
    if (end < prefix.length || !startsWith(prefix) || !endsWith(BACKUP_SUFFIX)) return null
    // Reading the id back is not enough: the parser also takes forms no write writes, the undashed one and
    // upper case among them, so only a name that spells the id the way a write spells it counts.
    val id = substring(prefix.length, end)
    return Uuid.parseOrNull(id)?.takeIf { it.toString() == id }
}

private const val BACKUP_SUFFIX = ".bak"

/**
 * The backups of this file that a write left beside it, in name order, or an empty list when there are none.
 *
 * The order says nothing about when each was made: a backup is named after its write, not after the clock.
 *
 * A write disposes of its own backup when it can, deleting it once the write succeeds and moving it back when
 * the write fails, so one left behind means a write could not get that far. A killed process leaves one; so
 * does a write that answered [FileError.RestoreFailed], which already named the backup it could not put back;
 * and so does a write that answered `Ok` and then failed to delete it.
 *
 * A leftover therefore marks the file as worth checking rather than proving it wrong, and neither copy is the
 * one to trust by default. The file can read perfectly and still say less than it did, since a write cut short
 * can leave text that still parses. The backup holds what the file had before that write began, unless the
 * process died while the copy was still running, which leaves a backup shorter than the file it copied.
 *
 * Only a backup a write of this file made is counted: each carries the id of its write, so a copy someone
 * kept beside the file under a name of their own is never one of these.
 *
 * Reading the folder the file sits in can fail, and the [FileError] then names that folder.
 */
public fun Path.leftoverBackups(): Result<List<Path>, FileError> {
    // A write backs up beside the file a symlink points at, so a leftover is looked for there as well. A path
    // that cannot be resolved is not there, and a backup only ever sits beside a file that is.
    val target = writeTarget()
    val holder = target.parent ?: return Ok(emptyList())
    return holder.list().map { entries ->
        entries.filter { it.name.backupIdOf(target.name) != null }.sortedBy { it.name }
    }
}

/** Whether nothing is at this path, as against a lookup that could not say which. */
private fun Path.isGone(): Boolean = metadata().errorOrNull() is FileError.NotFound

private fun Path.deleteQuietly() {
    try {
        SystemFileSystem.delete(this, mustExist = false)
    } catch (_: Exception) {
        // A leftover backup changes nothing about the outcome already decided.
    }
}
