package com.fromwau.kern.fs

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
 * Replaces this file's content with [text] as UTF-8, creating the file and any missing parent folders, by rename
 * or in place as [mode] decides.
 *
 * **By rename.** The content goes into `.<name>.<id>.tmp` beside the file, is forced to disk and renamed over
 * the file, so readers see the old content or the new and never part of it. A failure, or a killed process,
 * leaves the file as it was, plus at most that temp file. The new file takes the old one's permissions and
 * group, but other hard links keep the old content, this user owns it, and timestamps, ACLs and extended
 * attributes start fresh.
 *
 * **In place.** The old content is copied to a backup `.<name>.<id>.bak` beside the file, then the file itself
 * is written, so its inode, owner and links stay. A failed write moves the backup back; when that fails too, the
 * result is [FileError.RestoreFailed] and [leftoverBackups] finds the backup. Readers can see the file
 * half-written, and only one such write may run on a file at a time.
 *
 * Either way the write needs write permission on the folder, and a file this process may not read or write is
 * refused before anything changes. Anything at the path that is not a regular file is [FileError.NotRegularFile].
 * A write through a symlink lands at its target and the link stays; a link whose target is missing gets that
 * target created, though not the folders it needs.
 */
public fun Path.writeText(
    text: String,
    mode: WriteMode = WriteMode.Auto,
): EmptyResult<FileError> = writeBytes(text.encodeToByteArray(), mode)

/** [writeText] for raw bytes. */
public fun Path.writeBytes(
    bytes: ByteArray,
    mode: WriteMode = WriteMode.Auto,
): EmptyResult<FileError> {
    val target = writeTarget()
    val created = target.parent?.createDirectories()
    if (created is Result.Error) return created
    val existing = when (val found = metadata()) {
        is Result.Success -> found.value
        is Result.Error -> if (found.error is FileError.NotFound) null else return found
    }
    if (existing != null && !existing.isRegularFile) return Err(FileError.NotRegularFile(this, existing.type))

    val existed = existing != null
    return when (val way = replacementFor(mode, target, existed)) {
        Replacement.Rename -> writeByRename(target, bytes, existed, mode)
        Replacement.InPlace -> writeInPlace(target, bytes, existed)
        is Replacement.Refused -> Err(FileError.WriteFailed(this, way.reason))
    }
}

/** How one write replaces its target, decided from its [WriteMode] and what is at the target. */
private sealed interface Replacement {
    data object Rename : Replacement

    data object InPlace : Replacement

    data class Refused(val reason: String) : Replacement
}

// A target that is still a symlink could not be resolved, and a rename would put a regular file in the link's place.
private fun replacementFor(
    mode: WriteMode,
    target: Path,
    existed: Boolean,
): Replacement = when (mode) {
    WriteMode.InPlace -> Replacement.InPlace
    WriteMode.Auto -> when {
        isSymlink(target) -> Replacement.InPlace
        !existed -> Replacement.Rename
        renameReplacesAtomically && fileIdentity(target)?.let { whyRenameLoses(it) == null } == true ->
            Replacement.Rename
        else -> Replacement.InPlace
    }
    WriteMode.Atomic -> when {
        isSymlink(target) -> Replacement.Refused(
            "it is a symlink whose target cannot be resolved, and a rename would replace the link with a file",
        )
        !existed -> Replacement.Rename
        else -> fileIdentity(target)?.let(::whyRenameLoses)?.let { Replacement.Refused(it) } ?: Replacement.Rename
    }
}

/** What replacing a file of this [identity] by a renamed copy would lose, or null when nothing. */
private fun whyRenameLoses(identity: FileIdentity): String? = when {
    identity.linkCount > 1 -> "it has other hard links, which a rename would cut off"
    !identity.ownedByCurrentUser -> "another user owns it, and a rename would make it this user's"
    else -> null
}

private fun Path.writeByRename(
    target: Path,
    bytes: ByteArray,
    existed: Boolean,
    mode: WriteMode,
): EmptyResult<FileError> {
    val temp = target.besideWith(TEMP_SUFFIX)
    val failure = try {
        if (existed) {
            // A rename never opens the file, so without this it would replace one this process may not touch.
            SystemFileSystem.source(target).close()
            SystemFileSystem.sink(target, append = true).close()
        }
        // Empty until it has the file's group and permissions.
        SystemFileSystem.sink(temp).close()
        if (existed && !copyGroup(from = target, to = temp)) {
            temp.deleteQuietly()
            return if (mode == WriteMode.Atomic) {
                Err(FileError.WriteFailed(this, GROUP_NOT_KEPT))
            } else {
                writeInPlace(target, bytes, existed = true)
            }
        }
        // After the group, since a chown can clear the setuid and setgid bits.
        if (existed) copyPermissions(from = target, to = temp)
        SystemFileSystem
            .sink(temp)
            .buffered()
            .use { it.write(bytes) }
        val unsynced = syncFile(temp)
        if (unsynced == null) SystemFileSystem.atomicMove(temp, target)
        unsynced
    } catch (e: Exception) {
        e.reason
    }
    if (failure != null) {
        temp.deleteQuietly()
        return Err(FileError.WriteFailed(this, failure))
    }
    // The content is in place once renamed, so a folder that will not sync costs durability, not the write.
    val _ = syncDirectory(target.parent ?: Path("."))
    return Ok(Unit)
}

private fun Path.writeInPlace(
    target: Path,
    bytes: ByteArray,
    existed: Boolean,
): EmptyResult<FileError> {
    var backup: Path? = null
    val sink = try {
        if (existed) {
            backup = target.besideWith(BACKUP_SUFFIX)
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
 * Removes this file, symlink or empty directory. A symlink goes as a link, so what it points at stays, also
 * when that is missing. A directory that still holds entries is [FileError.WriteFailed]; [deleteRecursively]
 * clears a tree.
 */
public fun Path.delete(): EmptyResult<FileError> =
    // kotlinx-io looks a path up before removing it, following links, so it cannot remove a dangling one.
    if (isSymlink(this)) {
        deleteLink(this)?.let { Err(FileError.WriteFailed(this, it)) } ?: Ok(Unit)
    } else {
        deleteEntry()
    }

private fun Path.deleteEntry(): EmptyResult<FileError> = try {
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
 */
public fun Path.deleteRecursively(): EmptyResult<FileError> {
    // Removing first keeps a symlink a link: the tree below is only entered for a directory that resists.
    val removed = delete()
    // Listing a link would list what it points at, which is not this path's to remove.
    if (removed !is Result.Error || isSymlink(this)) return removed

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
    // Empty until it has the file's permissions.
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

/** A name beside this file ending in [suffix], carrying the id of the write that made it. */
private fun Path.besideWith(suffix: String): Path {
    val sibling = ".$name.${Uuid.random()}$suffix"
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

private const val TEMP_SUFFIX = ".tmp"

private const val GROUP_NOT_KEPT = "the new file could not be given its group, so a rename would change it"

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
