package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.fold
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import kotlinx.io.RawSink
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * Replaces this file's content with [text] as UTF-8, creating the file and any missing parent folders, by rename
 * or in place as [mode] decides. The folders are made as [createDirectories] makes them, every level the path passes
 * through as written, so `dir / "a" / ".." / "f"` makes `dir/a` and writes `dir/f`.
 *
 * **By rename.** The content goes into `.<name>.<id>.tmp` beside the file, is forced to disk and renamed over
 * the file, so readers see the old content or the new and never part of it. A failure, or a killed process,
 * leaves the file as it was, plus at most that temp file, which [leftovers] finds. The new file takes the old
 * one's permissions and group, but other hard links keep the old content, this user owns it, and timestamps,
 * ACLs and extended attributes start fresh, unless [modifiedAt] gives the modification time.
 *
 * **In place.** The old content is copied to `.<name>.<id>.bak.part` beside the file and, once complete, renamed to
 * the backup `.<name>.<id>.bak`; then the file itself is written, so its inode, owner and links stay. A failed write
 * copies the backup's content back into the file, which keeps all of these; when that fails too, the result is
 * [FileError.RestoreFailed] and [leftovers] finds the backup. Readers can see the file half-written, and only one
 * such write may run on a file at a time.
 *
 * A [Leftover.Backup] of the file beside it, beside its target for a write through a link, stops the write until a
 * [Writer.restore] or [Writer.discard] deals with it, as a [Writer] write stops. [openAppend] is never stopped.
 *
 * Either way the write needs write permission on the folder, and a file this process may not read or write is
 * refused before anything changes. Anything at the path that is not a regular file is [FileError.NotRegularFile].
 * A rename takes every name up to 255 bytes: for a name too long for `.<name>.<id>.tmp`, more than 213 bytes, the temp
 * is `.<id>.tmp`, which [leftoversIn] finds as a [Leftover.UnnamedTemp]. The partial backup is 47 bytes longer than
 * the file's name, so an in-place write of an existing file takes names of up to 208 bytes, and a longer one is
 * [FileError.NameTooLong] before anything is created. A [WriteMode.Auto] write that has made its temp and then has to
 * write in place deletes the temp before answering it.
 *
 * A path ending in `.` or `..` names a folder, as [delete] reads it, so its write is refused before anything is made:
 * [FileError.NotRegularFile] when that folder is there, [FileError.NotADirectory] naming the entry when a file or
 * anything else is there in its place, and [FileError.NotFound] when nothing is.
 *
 * A write through a symlink lands at its target and the link stays. A link whose target is missing, or a chain of
 * links ending in one, gets that target created, with the folders it needs; outside [WriteMode.InPlace] it is created
 * by rename too. To write below a folder of your app, where a link must never be written through, use a [Writer].
 *
 * @param ownerOnly leaves the file readable and writable by its owner alone, `600`, before any content goes in,
 *   instead of what the umask gives a new file or the permissions an existing one had. A rename's temp file is made
 *   `600`, and an in-place write narrows the file, and makes its backup `600`, before emptying it. Written in place,
 *   a file this user does not own cannot be narrowed, which is [FileError.WriteFailed]. Each missing folder the write
 *   makes on the way is `700`, as [createDirectories] makes them with `ownerOnly`. It applies on Linux, Apple,
 *   Android and the JVM on a file system with POSIX permissions, and Windows ignores it.
 * @param executable gives the file an execute bit for each class that may read it, at the moment [ownerOnly] sets its
 *   mode, before any content goes in: a new file is `755` under umask `022`, and `700` with [ownerOnly]. A write by
 *   rename gives them to the new file before it takes the name. An in-place write adds them to the file itself, so
 *   every hard link sees them, and a file this user does not own is then [FileError.WriteFailed]. False leaves the
 *   mode alone, so an existing script stays executable. Where it applies is as for [ownerOnly].
 * @param modifiedAt gives the file this modification time, as finely as the platform keeps it: a write by rename sets
 *   it on the new file before it takes the name, and an in-place write once the content is written. Null leaves the
 *   time the write gives it.
 * @return the [Written] way, [WriteWay.Renamed] or [WriteWay.InPlace], with the info of the file written;
 *   [FileError.PendingBackup] when a backup of the file waits beside it, with nothing written. A missing parent
 *   folder that could not be made is [FileError.WriteFailed] naming this path, with the folder as its
 *   [FailedAt.Folder]. Any other failure making the folders is what [createDirectories] answers.
 */
public fun Path.writeText(
    text: String,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Written, FileError> = writeBytes(text.encodeToByteArray(), mode, ownerOnly, executable, modifiedAt)

/** [writeText] for raw bytes, with the same answer. */
public fun Path.writeBytes(
    bytes: ByteArray,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Written, FileError> = write(mode, ownerOnly, executable = executable, modifiedAt = modifiedAt) {
    it.write(bytes)
}

/**
 * Copies this file's content to [target], replacing what [target] held the way [writeBytes] would, with the same
 * [mode], rules and errors, and creating its missing parent folders. The content is streamed, so the file is never
 * held in memory whole.
 *
 * Only the content is copied unless [keepAttributes] says otherwise: an existing [target] keeps its own permissions,
 * as after any write, and a new one gets the permissions any new file gets, unless [ownerOnly] leaves it `600` either
 * way, as [writeText] describes. A [target] that is this very file, under another name, through a hard link or
 * through a symlink, is left as it is and the answer is [Copied.Unchanged].
 *
 * A link at [target] is followed, as a write follows it.
 *
 * ```kotlin
 * script.copyTo(backupDir / script.name, keepAttributes = true)   // still executable, same modification time
 *     .onError { println("not backed up: $it") }
 * ```
 *
 * @param keepAttributes gives [target] this file's permissions before any content goes in, and this file's
 *   modification time once the content is in, to the precision both file systems keep, so the copy reads as the same
 *   file to tools that compare the two.
 *   [ownerOnly] wins over the permissions: the copy is `600` whatever this file's mode. Windows keeps the time; of the
 *   permissions, Windows native carries only the read-only flag, as every write there does, and the JVM there none.
 * @param executable adds an execute bit for each class that may read [target], as [writeText] describes, to whatever
 *   mode it gets otherwise: its own, the one [keepAttributes] carries over, or `600` with [ownerOnly].
 * @param modifiedAt gives [target] this modification time, as [writeText] sets it, in place of the one
 *   [keepAttributes] carries over.
 * @return how [target] was written, as [writeText] answers, or [Copied.Unchanged] for this file itself;
 *   [FileError.NotFound], [FileError.NotRegularFile] or [FileError.Inaccessible] naming this path when it is not a
 *   regular file this process may read, checked before [target] is touched; otherwise what writing [target] returns,
 *   where a read of this file that fails partway is the write's failure.
 */
public fun Path.copyTo(
    target: Path,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    keepAttributes: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Copied, FileError> {
    val found = when (val looked = metadata()) {
        is Result.Success -> looked.value
        is Result.Error -> return looked
    }
    if (!found.isRegularFile) return Err(FileError.NotRegularFile(this, typeOf(found)))
    target.refusedPath()?.let { return it }
    // Writing would empty the very file being read.
    if (isSameFile(this, target.namedEntry())) return info().map { Copied.Unchanged(it) }
    val kept = if (keepAttributes) {
        when (val read = keptAttributes()) {
            is Result.Success -> read.value
            is Result.Error -> return read
        }
    } else {
        null
    }
    val source = when (val opened = openSource()) {
        is Result.Success -> opened.value
        is Result.Error -> return opened
    }
    return target.writeFrom(source, mode, ownerOnly, kept, executable, modifiedAt)
}

/** What [copyTo] carries onto the file it writes: the source's [permissions], where it has them, and [modified]. */
internal class KeptAttributes(
    val permissions: Int?,
    val modified: Instant,
)

/** The modification time a write gives its file: [modifiedAt] when asked for, else the one [kept] from a source. */
internal fun modifiedTimeFor(
    modifiedAt: Instant?,
    kept: KeptAttributes?,
): Instant? = modifiedAt ?: kept?.modified

internal fun Path.keptAttributes(): Result<KeptAttributes, FileError> = try {
    Ok(KeptAttributes(permissionBits(this), fileInfoAt(this).modifiedAt))
} catch (e: Exception) {
    inaccessibleUnlessGone(e)
}

/** This file opened for reading, or with [streams] the FIFO or character device too, as [openFileSource] opens it. */
internal fun Path.openSource(streams: Boolean = false): Result<FileSource, FileError> = try {
    Ok(openFileSource(this, streams = streams))
} catch (e: NotRegularFileFailure) {
    Err(FileError.NotRegularFile(this, e.type))
} catch (e: Exception) {
    inaccessibleUnlessGone(e)
}

/** Replaces this file's content with everything [source] holds, as [writeBytes] would, and closes [source]. */
internal fun Path.writeFrom(
    source: RawSource,
    mode: WriteMode,
    ownerOnly: Boolean = false,
    kept: KeptAttributes? = null,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Written, FileError> = try {
    write(mode, ownerOnly, kept = kept, executable = executable, modifiedAt = modifiedAt) { it.transferFrom(source) }
} finally {
    source.closeQuietly()
}

/**
 * Replaces this file's content with what [content] puts into the sink it is handed, as [writeText] describes.
 * [content] runs at most once. [keepGroup] hands a renamed copy the file's group, as [copyGroup] does, and [backUp],
 * when set, makes an in-place write's backup in place of [WriteFolder.copyFile]. With [kept], the file gets those
 * permissions before any content and that modification time after it, as [copyTo] describes; without, [modifiedAt].
 */
internal fun Path.write(
    mode: WriteMode,
    ownerOnly: Boolean = false,
    keepGroup: (from: Path, to: Path) -> Boolean = ::copyGroup,
    kept: KeptAttributes? = null,
    backUp: ((from: Path, to: Path, ownerOnly: Boolean) -> HeldFile)? = null,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
    content: (Sink) -> Unit,
): Result<Written, FileError> {
    refusedPath()?.let { return it }
    val entry = namedEntry()
    if (entry != this) return Err(folderWriteError(entry))
    val target = writeTarget()
    val looked = metadata()
    if (looked is Result.Error && looked.error !is FileError.NotFound) {
        // The folders' own failure comes first, since it names the level that refused.
        val created = createFoldersFor(target, ownerOnly)
        return if (created is Result.Error) created else looked
    }
    val existing = (looked as? Result.Success)?.value
    if (existing != null && !existing.isRegularFile) return Err(FileError.NotRegularFile(this, typeOf(existing)))
    val existed = existing != null
    val identityDecides = mode == WriteMode.Atomic || mode == WriteMode.Auto && renameReplacesAtomically
    val identity = if (existed && identityDecides) fileIdentity(target) else null
    val way = replacementFor(mode, target, existed, identity)
    // Asked before any folder or file is made, so a name that cannot be written leaves nothing behind.
    sidecarTooLong(target, way, existed)?.let { return it }
    val folder = PathFolder(target.parent, keepGroup, backUp)
    folder.pendingBackup(target.name, this) { linkToCopies() }?.let { return Err(it) }
    val created = createFoldersFor(target, ownerOnly)
    if (created is Result.Error) return created
    val plan = WritePlan(
        path = this,
        folder = folder,
        name = target.name,
        content = content,
        mode = mode,
        ownerOnly = ownerOnly,
        permissions = if (ownerOnly) OWNER_READ_WRITE else kept?.permissions,
        executable = executable,
        modified = modifiedTimeFor(modifiedAt, kept),
    )
    return when (way) {
        Replacement.Rename -> plan.byRename(existed)
        Replacement.InPlace -> plan.inPlace(existed)
        is Replacement.Refused -> Err(FileError.AtomicRefused(this, way.why))
    }
}

/**
 * Why a write of this path, which names the folder [entry] by a trailing `.` or `..`, is refused:
 * [FileError.NotRegularFile] when a folder is there, [FileError.NotADirectory] naming [entry] when something else is,
 * [FileError.NotFound] when nothing is, or the error looking it up.
 */
internal fun Path.folderWriteError(entry: Path): FileError = when (val looked = entry.metadata()) {
    is Result.Success -> when {
        looked.value.isDirectory -> FileError.NotRegularFile(this, FileType.Directory)
        else -> FileError.NotADirectory(entry, entry.typeOf(looked.value))
    }
    is Result.Error -> when (looked.error) {
        is FileError.NotFound -> FileError.NotFound(this)
        else -> looked.error
    }
}

/**
 * Creates the missing folders [target], the file a write of this path lands in, passes through. A level that could not
 * be made is a [FileError.WriteFailed] naming this path, with that level as its [FailedAt.Folder]; any other error
 * is what [createDirectories] answers.
 */
private fun Path.createFoldersFor(
    target: Path,
    ownerOnly: Boolean,
): EmptyResult<FileError> {
    val created = makeFolders(target.parent ?: return Ok(Unit), pathFolderMode(ownerOnly))
    val failed = (created as? Result.Error)?.error as? FileError.WriteFailed ?: return created
    return Err(failed.copy(path = this, at = FailedAt.Folder(failed.path)))
}

/**
 * [FileError.NameTooLong] for a write of this path that replaces [target] the [way] given, when the partial backup an
 * in-place write of a file that [existed] makes beside [target] has a name longer than a file system takes. Null when
 * it fits, or when the write makes none; a rename's temp always fits, as [tempBeside] names it.
 */
private fun Path.sidecarTooLong(
    target: Path,
    way: Replacement,
    existed: Boolean,
): Err<FileError.NameTooLong>? {
    if (way != Replacement.InPlace || !existed) return null
    return tooLongBeside(target.besideWith(PARTIAL_BACKUP_SUFFIX), LongName.PartialBackup)
}

/** How one write replaces its target, decided from its [WriteMode] and what is at the target. */
private sealed interface Replacement {
    data object Rename : Replacement

    data object InPlace : Replacement

    data class Refused(val why: AtomicRefusal) : Replacement
}

// A target that is still a symlink is one this platform cannot follow, and a rename would put a regular file in
// the link's place.
private fun replacementFor(
    mode: WriteMode,
    target: Path,
    existed: Boolean,
    identity: FileIdentity?,
): Replacement = when (mode) {
    WriteMode.InPlace -> Replacement.InPlace
    WriteMode.Auto -> when {
        isSymlink(target) -> Replacement.InPlace
        !existed -> Replacement.Rename
        renameReplacesAtomically && identity?.let { whyRenameLoses(it) == null } == true ->
            Replacement.Rename
        else -> Replacement.InPlace
    }
    WriteMode.Atomic -> when {
        isSymlink(target) -> Replacement.Refused(AtomicRefusal.Symlink)
        !existed -> Replacement.Rename
        else -> identity?.let(::whyRenameLoses)?.let { Replacement.Refused(it) } ?: Replacement.Rename
    }
    WriteMode.Replace -> if (isSymlink(target)) Replacement.InPlace else Replacement.Rename
}

/** What replacing a file of this [identity] by a renamed copy would lose, or null when nothing. */
private fun whyRenameLoses(identity: FileIdentity): AtomicRefusal? = when {
    identity.hasOtherLinks -> AtomicRefusal.OtherHardLinks
    !identity.ownedByCurrentUser -> AtomicRefusal.OtherOwner
    else -> null
}

/**
 * One write of [path] into the file [name] in [folder], the file it lands in, as a write worked it out: the
 * [permissions] and [modified] time the file gets, where it has them, whether it is made [executable], whether its
 * temp and backup are made for the owner alone, [ownerOnly], and which [mode] decides a group that cannot be kept.
 * A new file without [permissions] asks for [newFileMode], which the umask narrows.
 */
internal class WritePlan(
    val path: Path,
    val folder: WriteFolder,
    val name: String,
    val content: (Sink) -> Unit,
    val mode: WriteMode,
    val ownerOnly: Boolean,
    val permissions: Int?,
    val executable: Boolean,
    val modified: Instant?,
    val newFileMode: Int = NEW_FILE_MODE,
) {
    private val target: Path = folder.pathOf(name)

    fun byRename(existed: Boolean): Result<Written, FileError> {
        val temp = target.tempBeside()
        var written: FileInfo? = null
        val failure = try {
            if (existed) {
                // A rename never opens the file, so without this it would replace one this process may not touch.
                failingAt(FailedAt.LinkTarget(target)) {
                    folder.openSource(name).close()
                    folder.openSink(name, append = true).close()
                }
            }
            // Empty until it has the file's group and permissions.
            val made = failingAt(FailedAt.Temp(temp)) {
                folder.createHeldFile(temp.name, OWNER_READ_WRITE.takeIf { ownerOnly }, newFileMode)
            }
            val renames = made.use { copy ->
                val groupKept = !existed || folder.copyGroup(name, temp.name)
                val renaming = groupKept || mode == WriteMode.Replace
                if (renaming) {
                    // After the group, since a chown can clear the setuid and setgid bits. A group that was not kept
                    // gets none of the file's group permissions, which were meant for the group the copy does not have.
                    val keep = if (groupKept) PERMISSION_BITS else PERMISSION_BITS and GROUP_BITS.inv()
                    when {
                        permissions != null -> copy.setPermissions(permissions and keep)
                        existed -> folder.permissionBits(name)?.let { copy.setPermissions(it and keep) }
                    }
                    if (executable) {
                        folder.permissionBits(temp.name)?.let { copy.setPermissions(it.withExecuteForReaders()) }
                    }
                    copy
                        .sink()
                        .buffered()
                        .use(content)
                    modified?.let { copy.setModifiedTime(it) }
                    copy.sync()
                    failingAt(FailedAt.Temp(temp)) { copy.moveTo(target) }
                    // Read after the rename, which moves the file's changedAt on most file systems.
                    written = failingAt(FailedAt.LinkTarget(target)) { copy.info() }
                }
                renaming
            }
            if (!renames) {
                folder.deleteQuietly(temp.name)
                if (mode == WriteMode.Atomic) return Err(FileError.AtomicRefused(path, AtomicRefusal.GroupNotKept))
                path.sidecarTooLong(target, Replacement.InPlace, existed = true)?.let { return it }
                return inPlace(existed = true)
            }
            null
        } catch (e: Exception) {
            e
        }
        if (failure != null) {
            folder.deleteQuietly(temp.name)
            return Err(failure.writeError(path))
        }
        // The content is in place once renamed, so a folder that will not sync costs durability, not the write.
        val _ = folder.sync()
        return Ok(Written(WriteWay.Renamed, checkNotNull(written)))
    }

    fun inPlace(existed: Boolean): Result<Written, FileError> {
        var partPath: Path? = null
        var backupPath: Path? = null
        var backup: HeldFile? = null
        val sink = try {
            if (existed) {
                val id = newWriteId()
                partPath = target.besideWith(PARTIAL_BACKUP_SUFFIX, id)
                backup = failingAt(FailedAt.Backup(partPath)) { folder.backUp(name, partPath.name, ownerOnly) }
                // Only a backup holding all the file had is ever named as one, so a crash during the copy cannot pass
                // for a backup worth restoring.
                backupPath = target.besideWith(BACKUP_SUFFIX, id)
                failingAt(FailedAt.Backup(partPath)) { backup.moveTo(backupPath) }
            }
            // Opening empties the file, so an open that fails has changed nothing and needs no restore.
            failingAt(FailedAt.LinkTarget(target)) {
                folder.openSink(name, append = false, inPlaceMode(existed), newFileMode)
            }
        } catch (e: Exception) {
            backup?.closeQuietly()
            partPath?.let { folder.deleteQuietly(it.name) }
            backupPath?.let { folder.deleteQuietly(it.name) }
            return Err(e.writeError(path))
        }

        return try {
            // A new file has the mode the umask gave it only once it is made.
            if (executable && permissions == null && !existed) {
                failingAt(FailedAt.LinkTarget(target)) {
                    val made = sink.permissionBits()
                    if (made != null) sink.setPermissions(made.withExecuteForReaders())
                }
            }
            val written = sink.buffered().use {
                content(it)
                // Every byte is in before the time is set and the file read, so nothing written later moves either.
                it.flush()
                if (modified != null) sink.setModifiedTime(modified)
                failingAt(FailedAt.LinkTarget(target)) { sink.info() }
            }
            backup?.closeQuietly()
            backupPath?.let { folder.deleteQuietly(it.name) }
            Ok(Written(WriteWay.InPlace, written))
        } catch (e: Exception) {
            val undo = if (backupPath != null && backup != null) Undo.Restore(backupPath, backup) else Undo.Remove
            val undone = undoFailure(undo, folder, name) ?: return Err(writeFailed(path, e))
            // Only a backup proved gone goes unnamed: pointing a recovery at a path that holds nothing is worse than
            // saying there is none, and a lookup that cannot answer has proved nothing.
            Err(restoreFailed(path, undone, backup = backupPath?.takeUnless { folder.isGone(it.name) }))
        } finally {
            backup?.closeQuietly()
        }
    }

    /**
     * The mode an in-place write gives the file before emptying it, so a file this user may not change the mode of is
     * refused as it was; null to leave it as it is, or as the umask makes a new one.
     */
    private fun inPlaceMode(existed: Boolean): Int? {
        if (!executable) return permissions
        val base = permissions ?: if (existed) folder.permissionBits(name) else null
        return base?.withExecuteForReaders()
    }
}

/**
 * Removes this file, symlink or empty directory. A symlink goes as a link, so what it points at stays, also
 * when that is missing. A directory that still holds entries is [FileError.WriteFailed]; [deleteRecursively]
 * clears a tree. A path ending in `.` or `..` names a folder by name alone, `dir / "."` and `dir / "sub" / ".."` both
 * `dir`, whatever `sub` is, and that folder is removed.
 *
 * @return `Ok` once the entry is gone; [FileError.NotFound] when nothing is there; [FileError.WriteFailed] when it
 *   could not be removed.
 */
public fun Path.delete(): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    val entry = namedEntry()
    // kotlinx-io looks a path up before removing it, following links, so it cannot remove a dangling one.
    return if (isSymlink(entry)) {
        deleteLink(entry)?.let { Err(writeFailed(entry, it)) } ?: Ok(Unit)
    } else {
        entry.deleteEntry()
    }
}

private fun Path.deleteEntry(): EmptyResult<FileError> = try {
    SystemFileSystem.delete(this, mustExist = true)
    Ok(Unit)
} catch (e: Exception) {
    metadata().fold(
        // It is there, so the removal itself is what failed: a directory with entries, or a read-only parent.
        onSuccess = { whyNotDeleted(this, e)?.let { Err(writeFailed(this, it)) } ?: Ok(Unit) },
        onError = { Err(it) },
    )
}

/**
 * Why removing [path] failed, given the [failure] kotlinx-io raised, or null when asking removed it after all.
 * The JVM's own delete reports no reason, so there the removal is tried once more in a way that says why.
 */
internal expect fun whyNotDeleted(path: Path, failure: Exception): Exception?

/**
 * Makes the one folder [path] in its existing parent with the permission bits [mode], which the umask narrows, where
 * the platform has permissions. True once made, and false when a folder, or a link to one, is already at [path];
 * throws what the platform said when it could not be made, also when anything else is already there.
 */
internal expect fun makeFolder(
    path: Path,
    mode: Int,
): Boolean

/** Whether a folder, or a link to one, is at [path] now, for [makeFolder] to tell one already there from a failure. */
internal fun isFolderAt(path: Path): Boolean = path.metadata().getOrNull()?.isDirectory == true

/**
 * Moves this file or folder to [target] in one step, so nothing ever sees it half moved, and the old path is
 * gone once this succeeds. A symlink moves as itself rather than as what it points at, also one whose target is
 * missing. The missing folders [target] passes through are created first, as [createDirectories] makes them, and a
 * failure there is what [createDirectories] returns.
 *
 * Both paths must be on one file system: a move across two is [FileError.WriteFailed], so [copyTo] and [delete]
 * instead. A file already at [target] is replaced on the JVM, Android, Linux and Apple; whether Windows native
 * replaces it has not been verified. A link at [target] is replaced as a link. A file moved onto a folder, empty or
 * not, is [FileError.NotRegularFile] naming [target], as [copyTo] answers, and so is a link, even one to a folder.
 * A folder replaces only an empty folder, and only where the OS allows it: one moved onto anything else is
 * [FileError.NotADirectory] naming [target] with what is there, [FileType.Symlink] for a link, whatever it leads to.
 * Nothing at this path is [FileError.NotFound], answered before any folder is created; every other failure is
 * [FileError.WriteFailed] naming [target], since the source is evidently there. This path or [target] ending in `.` or
 * `..` is the folder it names, as [delete] reads it, and the folders a trailing `.` or `..` passes through are not
 * created.
 */
public fun Path.moveTo(target: Path): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    val moved = namedEntry()
    val sourceIsLink = isSymlink(moved)
    // A link moves as itself, never as the folder it may point at, and one whose target is missing is still there.
    val source = if (sourceIsLink) {
        null
    } else {
        when (val looked = moved.metadata()) {
            is Result.Success -> looked.value
            is Result.Error -> return looked
        }
    }
    target.refusedPath()?.let { return it }
    val destination = target.namedEntry()
    val moving = if (source == null) FileType.Symlink else moved.typeOf(source)
    val there = if (isSymlink(destination)) FileType.Symlink else destination.fileType().getOrNull()
    moveRefusal(moving, there, destination)?.let { return Err(it) }
    // Only above the entry the target names: a trailing dot name's levels are not made.
    val created = destination.parent?.let { makeFolders(it) }
    if (created is Result.Error) return created
    // kotlinx-io looks the source up first, following links, so it cannot move a link whose target is missing.
    if (sourceIsLink) return moveLink(moved, destination)?.let { Err(writeFailed(destination, it)) } ?: Ok(Unit)
    return try {
        SystemFileSystem.atomicMove(moved, destination)
        Ok(Unit)
    } catch (e: Exception) {
        moved.metadata().fold(
            onSuccess = { Err(writeFailed(destination, e)) },
            onError = { Err(it) },
        )
    }
}

/**
 * Why moving a [moving] onto [there], each as it is without following a link, so a link is [FileType.Symlink], is
 * refused, naming [destination]; null when it may go ahead, [there] null being nothing. A link at the destination is
 * replaced as a link, so only a real folder there refuses a file or a link, and a folder replaces only a folder.
 */
internal fun moveRefusal(
    moving: FileType,
    there: FileType?,
    destination: Path,
): FileError? = when {
    (moving == FileType.Regular || moving == FileType.Symlink) && there == FileType.Directory ->
        FileError.NotRegularFile(destination, FileType.Directory)
    moving == FileType.Directory && there != null && there != FileType.Directory ->
        FileError.NotADirectory(destination, there)
    else -> null
}

/**
 * Removes this path and, when it is a directory, everything under it, like `rm -rf`. Every entry goes as it is,
 * so a symlink is removed as a link and its target is untouched. Whatever is already gone, this path included,
 * counts as removed, so a missing path is `Ok`. Any other failure stops the walk and is returned, which can leave
 * part of the tree behind: a folder with entries that may not be listed is [FileError.Inaccessible]. A path ending in
 * `.` or `..` names a folder, as [delete] reads it, and that folder's whole tree is removed.
 */
public fun Path.deleteRecursively(): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    return namedEntry().deleteTree()
}

private fun Path.deleteTree(): EmptyResult<FileError> {
    // Removing first keeps a symlink a link: the tree below is only entered for a directory that resists.
    val removed = delete()
    // Listing a link would list what it points at, which is not this path's to remove.
    if (removed !is Result.Error || isSymlink(this)) return removed

    val children = when (val listed = list()) {
        is Result.Success -> listed.value
        // Gone already counts as removed, and no directory leaves the removal's own failure to explain itself.
        is Result.Error -> return when (listed.error) {
            is FileError.NotFound -> Ok(Unit)
            is FileError.NotADirectory -> removed
            else -> listed
        }
    }
    for (child in children) {
        val childRemoved = child.deleteRecursively()
        if (childRemoved is Result.Error) return childRemoved
    }
    val emptied = delete()
    return if (emptied is Result.Error && emptied.error is FileError.NotFound) Ok(Unit) else emptied
}

/**
 * Creates this directory and any missing parents, like `mkdir -p`. Succeeds when it already exists as a
 * directory or a symlink to one, also when another process creates it at the same time. A file at the path, or
 * in place of one of its parents, is [FileError.NotADirectory] naming that file, and a path that may not be
 * looked up at all is [FileError.Inaccessible] naming that path. A symlink whose target is missing, at the path or in
 * place of a parent, is [FileError.WriteFailed] naming the link, and its target is not created. Every missing level
 * the path passes through is made as written, a `.` or `..` in it included:
 * `(dir / "a" / "ok" / "..").createDirectories()` makes `dir/a` and `dir/a/ok`.
 *
 * @param ownerOnly makes each level this call creates usable by its owner alone, `700`, from the moment it exists, as
 *   the XDG base directory spec asks of a missing base folder; a level already there keeps its permissions, so check
 *   one that must stay private yourself, as [singleInstance] checks its `state`. It applies on Linux, Apple, Android
 *   and the JVM on a file system with POSIX permissions, and Windows ignores it.
 */
public fun Path.createDirectories(ownerOnly: Boolean = false): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    return makeFolders(this, pathFolderMode(ownerOnly))
}

/**
 * Makes [folder] and every missing level it passes through as written, as `mkdir -p` does, so `a/../b` makes `a` and
 * then `b`, each with [mode] as [makeFolder] takes it, and [folder] itself with [folderMode]. A [folder] ending in `.`
 * or `..` names a level it passes through, so every level then takes [folderMode]. A level that could not be made is
 * [FileError.WriteFailed] naming that level.
 */
internal fun makeFolders(
    folder: Path,
    mode: Int = FOLDER_MODE,
    folderMode: Int = mode,
): EmptyResult<FileError> {
    val above = if (folder.name == "." || folder.name == "..") folderMode else mode
    val missing = mutableListOf<Path>()
    // Only the levels below the deepest existing directory: on Windows, an ancestor can be a UNC server name.
    for (level in generateSequence(folder) { it.parent }) {
        if (level.name == "." || level.name == "..") continue
        val held = when (val here = level.directoryHere()) {
            is Result.Success -> here.value
            is Result.Error -> return here
        }
        if (held) break
        missing += level
    }
    for (level in missing.asReversed()) {
        val failure = try {
            val _ = makeFolder(level, if (level == folder) folderMode else above)
            continue
        } catch (e: Exception) {
            e
        }
        // A level another process made since the failure is a folder all the same, and a file put there says so.
        val held = when (val here = level.directoryHere()) {
            is Result.Success -> here.value
            is Result.Error -> return here
        }
        if (!held) return Err(writeFailed(level, failure))
    }
    return Ok(Unit)
}

/**
 * Whether a directory is already at this path. A path nothing is at answers false, and one holding something
 * else is [FileError.NotADirectory]. One that could not be looked up answers neither, since treating it as
 * missing would ask for a level that may well be there.
 */
private fun Path.directoryHere(): Result<Boolean, FileError> = metadata().fold(
    onSuccess = { if (it.isDirectory) Ok(true) else Err(FileError.NotADirectory(this, typeOf(it))) },
    onError = { if (it is FileError.NotFound) Ok(false) else Err(it) },
)

/** What undoing a failed write has to do, so that having neither a backup nor a new file cannot be asked for. */
internal sealed interface Undo {
    /** The file was there, so the backup at [backup], still open as [copy], holds what it held to put back. */
    data class Restore(
        val backup: Path,
        val copy: HeldFile,
    ) : Undo

    /** The file was new, so whatever the write left at the path is removed. */
    data object Remove : Undo
}

/**
 * Undoes a write that failed after emptying the file [name] in [folder], which also holds the backup, as [undo] says.
 * Answers the failure that stopped it, marked with the path it was refused at as [failingAt] marks it, or null when
 * it worked.
 *
 * Copying the backup back writes into the file itself, so it keeps its inode, owner and other hard links. That can
 * fail for reasons of its own, such as the file no longer being writable, and they are not the ones the write failed
 * for. The backup is read through the open it was made with, and deleted only once its content is back.
 */
internal fun undoFailure(
    undo: Undo,
    folder: WriteFolder,
    name: String,
): Exception? = try {
    val over = folder.pathOf(name)
    when (undo) {
        is Undo.Restore -> {
            val failed = undo.copy
                .source()
                .buffered()
                .use { source -> source.putBackInto { folder.openSink(name, append = false) } }
            when (failed) {
                null -> Unit
                is PutBackFailure.Unread -> throw LocatedFailure(FailedAt.Backup(undo.backup), failed.failure)
                else -> throw LocatedFailure(FailedAt.LinkTarget(over), failed.failure)
            }
            undo.copy.closeQuietly()
            folder.deleteQuietly(undo.backup.name)
        }
        // A path that cannot be looked up is tried anyway, so its own failure is reported instead of ignored.
        Undo.Remove -> failingAt(FailedAt.LinkTarget(over)) { if (!folder.isGone(name)) folder.delete(name) }
    }
    null
} catch (e: Exception) {
    e
}

/**
 * This new copy with [permissions] put on it, when there are any, and then everything [open] opens, handed back still
 * open; closed again when either fails.
 */
internal fun HeldFile.filledFrom(
    permissions: () -> Int?,
    open: () -> RawSource,
): HeldFile {
    try {
        permissions()?.let { setPermissions(it) }
        open()
            .buffered()
            .use { source ->
                sink()
                    .buffered()
                    .use { it.transferFrom(source) }
            }
    } catch (e: Exception) {
        closeQuietly()
        throw e
    }
    return this
}

/**
 * Replaces the content of the file [open] opens, emptied, for writing with what is left in this backup, in the file
 * itself, so its inode, owner and other hard links stay. Answers the step that failed, or null.
 */
internal fun Source.putBackInto(open: () -> RawSink): PutBackFailure? {
    try {
        // Read first, so a backup that cannot be read empties nothing.
        val _ = request(1)
    } catch (e: Exception) {
        return PutBackFailure.Unread(e)
    }
    val sink = try {
        open()
    } catch (e: Exception) {
        return PutBackFailure.NotOpened(e)
    }
    return try {
        sink
            .buffered()
            .use { it.transferFrom(this) }
        null
    } catch (e: Exception) {
        PutBackFailure.NotWritten(e)
    }
}

/** The step of [putBackInto] that failed, with its [failure]. */
internal sealed interface PutBackFailure {
    val failure: Exception

    /** The backup could not be read, so the file was left as it was. */
    data class Unread(override val failure: Exception) : PutBackFailure

    /** The file could not be opened, so it was left as it was. */
    data class NotOpened(override val failure: Exception) : PutBackFailure

    /** Writing the file failed after it was emptied. */
    data class NotWritten(override val failure: Exception) : PutBackFailure
}

/**
 * The file a write acts on: what a symlink points at, so the temp file, the backup and a restore land beside it
 * and the link survives. A chain of links whose last target is missing leads to that missing path. A loop, like any
 * path that cannot be resolved, is used as given.
 */
@OptIn(InternalKernApi::class)
internal fun Path.writeTarget(): Path = try {
    if (SystemFileSystem.exists(this)) SystemFileSystem.resolve(this) else linkChainEnd() ?: this
} catch (_: Exception) {
    this
}

/**
 * The longest name, in bytes of UTF-8, that a link switch or a stage takes: [switchSymlinkTo], [Writer.switchSymlink],
 * [createDirectoryAtomically] and [Writer.createDirectoryAtomically] make a name 43 bytes longer beside it,
 * `.<name>.<id>.link` or the claim `.<name>.<id>.lock`, which must fit in the 255 a name may take. A longer one is
 * [FileError.NameTooLong].
 */
public const val MAX_STAGED_NAME_BYTES: Int =
    LONGEST_NAME_BYTES - "..".length - WRITE_ID_LENGTH - STAGED_LINK_SUFFIX.length

/**
 * [FileError.NameTooLong] naming this path and [name], counting the bytes of [staged], the link or claim made beside
 * it, when this path's name is longer than [MAX_STAGED_NAME_BYTES]; null when it fits.
 */
internal fun Path.tooLongToStage(
    staged: Path,
    name: LongName,
): Err<FileError.NameTooLong>? {
    if (!this.name.isTooLongToStage()) return null
    return Err(FileError.NameTooLong(this, name, staged.name.encodeToByteArray().size))
}

/**
 * [FileError.NameTooLong] naming this path and [name], when [sibling], a name made beside a file, takes more bytes in
 * UTF-8 than a name may; null when it fits.
 */
internal fun Path.tooLongBeside(
    sibling: Path,
    name: LongName,
): Err<FileError.NameTooLong>? {
    val bytes = sibling.name.encodeToByteArray().size
    return if (bytes <= LONGEST_NAME_BYTES) null else Err(FileError.NameTooLong(this, name, bytes))
}

/** A name beside this file ending in [suffix], carrying [id], the id of the write that made it. */
internal fun Path.besideWith(
    suffix: String,
    id: Uuid = newWriteId(),
): Path = sibling(".$name.$id$suffix")

/**
 * The temp a rename write of this file makes beside it, `.<name>.<id>.tmp`, or `.<id>.tmp` when the name is too long
 * for that.
 */
private fun Path.tempBeside(): Path {
    val id = newWriteId()
    val named = besideWith(TEMP_SUFFIX, id)
    if (named.name.encodeToByteArray().size <= LONGEST_NAME_BYTES) return named
    return unnamedTempBeside(id)
}

/** The temp `.<id>.tmp` beside this file, which names no file, for a write whose name is too long to name it. */
internal fun Path.unnamedTempBeside(id: Uuid): Path = sibling(".$id$TEMP_SUFFIX")

/**
 * Whether nothing is at this path, links followed, so a symlink whose target is missing is gone; as against a lookup
 * that could not say which.
 */
internal fun Path.isGone(): Boolean = metadata().errorOrNull() is FileError.NotFound

/** Whether nothing at all is at this path, links not followed, so not even a symlink whose target is missing. */
internal fun Path.isVacant(): Boolean = fileType(followLinks = false).errorOrNull() is FileError.NotFound

/** Closes this once its outcome is decided, which a close that fails cannot change. */
internal fun AutoCloseable.closeQuietly() {
    try {
        close()
    } catch (_: Exception) {
        // Nothing left to report it to: what was written or read is already answered for.
    }
}
