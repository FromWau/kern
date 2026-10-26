package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrElse
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import kotlinx.io.RawSource
import kotlinx.io.Sink
import kotlinx.io.files.Path
import kotlin.time.Duration
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * A [Writer] on this folder, which every file, folder, link and lock of yours below it goes through. The folder and
 * each missing level on the way are made `755`, or narrower as the umask says. A symlink here whose target is missing
 * stays: the folder it leads to is made, with each missing level above it, and is the writer's root. Through a chain
 * of such links, that is the folder the last one leads to. A target lying below another dangling link makes nothing.
 *
 * ```kotlin
 * val data = dirs.data.writer().getOrElse { return warn(it) }
 * data.writeText("notes/today.md", text).onError { println("not saved: $it") }
 * ```
 *
 * @param create false makes nothing, for a folder that must already be there, such as a backup disk's mount point.
 * @return the writer, its [Writer.root] this folder's real path; [FileError.NotFound] naming this path for a missing
 *   folder or a symlink whose target is missing with `create = false`; [FileError.NotADirectory] when something else
 *   is there, or naming a file the target of a dangling link lies below; [FileError.WriteFailed] naming a dangling
 *   link the target lies below; [FileError.Inaccessible] with [IoCause.Loop] for links that go round; or what making
 *   or resolving the folder answers.
 */
public fun Path.writer(create: Boolean = true): Result<Writer, FileError> = newWriter(rootRule = null, create)

/**
 * A private [Writer] on this folder: what it makes is for its owner alone, and it refuses a root or a file someone
 * else may change or read, as [Writer.isPrivate] describes. The folder is made `700` and each missing level above it
 * `755`, or narrower as the umask says, as [writer] makes them; with `create = false` nothing is made. Run `chmod 700`
 * on a folder others may read before using it here.
 *
 * ```kotlin
 * val secrets = dirs.config.privateWriter().getOrElse { return warn(it) }
 * secrets.writeText("token", token).onError { println("token not saved: $it") }
 * ```
 *
 * @return the writer; [FileError.Refused] naming the folder when another user owns it or its group or everyone else may
 *   write or read it, with [FileError.Refused.why] saying which; otherwise what [writer] answers.
 */
public fun Path.privateWriter(create: Boolean = true): Result<Writer, FileError> =
    newWriter(RootRule.Private, create)

/** What a private [Writer] refuses of its root, judged on every call. */
internal enum class RootRule {
    /** Another owner, or others who may write or read it. */
    Private,

    /** Another owner, or others who may write it, so a folder others may only read is taken. */
    Trusted,
}

/**
 * A [Writer] on this folder, private under [rootRule] unless that is null, making the missing levels when [create]
 * says so.
 */
internal fun Path.newWriter(
    rootRule: RootRule?,
    create: Boolean,
    asked: Path = this,
): Result<Writer, FileError> {
    if (create) {
        refusedPath()?.let { return it }
        val end = if (isSymlink(this)) linkChainEnd() else null
        end?.fileAbove()?.let { return Err(it) }
        val folder = if (end != null && isGone()) end else this
        val folderMode = writerFolderMode(isPrivate = rootRule != null)
        makeFolders(folder, SHARED_FOLDER_MODE, folderMode).onError { return Err(it) }
    } else {
        val type = fileType().getOrElse { return Err(it) }
        if (type != FileType.Directory) return Err(FileError.NotADirectory(this, type))
    }
    val real = realPath().getOrElse { return Err(it) }
    return writerAt(real, rootRule, asked)
}

/**
 * [FileError.NotADirectory] naming the first level above this path, from the top, that is there and is no folder, or
 * null. Each level is looked up on its own, since a lookup through a file fails differently on each platform and JDK.
 */
private fun Path.fileAbove(): FileError.NotADirectory? {
    for (level in generateSequence(parent) { it.parent }.toList().asReversed()) {
        val type = try {
            ownFileTypeAt(level)
        } catch (_: Exception) {
            return null
        } ?: return null
        if (type != FileType.Directory && type != FileType.Symlink) return FileError.NotADirectory(level, type)
    }
    return null
}

/**
 * A [Writer] rooted at [root], a path no link leads through, private under [rootRule] unless that is null. Nothing is
 * made, and a link at [root] itself is never followed: it is [FileError.FolderReplaced], as a missing folder is.
 */
internal fun writerAt(
    root: Path,
    rootRule: RootRule?,
    asked: Path = root,
): Result<Writer, FileError> = try {
    openLevel(root).use { level ->
        val key = level.key()
        val writer = Writer(root, root, rootRule, rootKey = key, boundaryKey = key)
        writer.refusedRoot(level, asked)?.let { Err(it) } ?: Ok(writer)
    }
} catch (_: LevelMoved) {
    Err(FileError.FolderReplaced(root))
} catch (e: Exception) {
    Err(inaccessible(root, e))
}

/**
 * Writes, reads, moves, removes and locks below one folder, its [root], so a name you hand it can never reach outside.
 * Make one with [writer] or [privateWriter] and keep it as long as you like: it holds nothing open and needs no
 * closing. It reads as the [Reader] it is, and [reader] hands code that only reads a view that cannot write.
 *
 * The rules every call keeps:
 * - A name is a relative path such as `"logs/app.log"`, checked by [relativePathProblem] before anything is looked
 *   at. One it refuses is [FileError.InvalidRelativePath] and nothing is touched. A name shaped like a write's
 *   leftover, [NameProblem.LeftoverName], is refused only by a call that would make an entry there, so a leftover
 *   can still be read, moved away and removed.
 * - Every call opens the root again and makes sure it is still the folder this writer was made for. One moved, removed
 *   or replaced is [FileError.FolderReplaced], and nothing is done.
 * - A symlink among the folders on the way is followed only while where it leads stays below the root, or for a
 *   child writer below the boundary of the writer it was made from. One that leads anywhere else is
 *   [FileError.Escapes] and nothing outside is touched. A symlink whose target steps out of those bounds on its way
 *   is refused, even when it comes back in.
 * - A symlink at the name itself is never written through. A write or an append refuses it unless asked to replace
 *   it, a lock replaces it with a regular file, [delete] and [move] act on the link, and a read follows it under the
 *   rule above.
 * - A write never changes the file in place unless you ask for it with `inPlace`, so other hard links to it keep their
 *   old content.
 * - A shared writer makes each folder `755` and each file `644` at most, `755` for an `executable` one or one copied
 *   with `keepAttributes`, its root, locks and appended files included: the umask may narrow that but never widen it.
 *   A file a write replaces by rename keeps that file's own mode.
 *
 * On the JVM, Android, macOS and Windows a folder swapped for a link just as a call reaches it can be followed, and on
 * Windows a root replaced by another folder can go unnoticed.
 */
public class Writer internal constructor(
    override val root: Path,
    internal val boundary: Path,
    /** What this writer refuses of its root when it is private, or null for a shared one. */
    internal val rootRule: RootRule?,
    private val rootKey: Any?,
    internal val boundaryKey: Any?,
    /** Where [boundary] is placed once built, for a stage's writer and its children, whose errors name it; or null. */
    internal val placedBoundary: Path? = null,
) : Reader {
    /** This writer's reads alone, as a [Reader] that cannot be cast back to a [Writer]. */
    public val reader: Reader = ReadOnlyReader(this)

    /**
     * Whether this writer is private. A private one makes files `600` and folders `700`, and refuses a root another
     * user owns or whose group or everyone else may write or read it, judged on every call. It reads only what
     * [readText] with `trusted` reads, and only a file its group and everyone else may not read, which is otherwise
     * [FileError.Refused] with [RefusedBecause.OthersMayRead]. The private writers [singleInstance] and
     * [clearStaleTempRuns] work through take a root others may only read. On Windows, which keeps no such permissions,
     * it is the same as a shared one.
     */
    public val isPrivate: Boolean get() = rootRule != null

    override fun toString(): String = "Writer(root=$root, isPrivate=$isPrivate)"

    /** The mode each folder this writer makes is made with. */
    internal val folderMode: Int get() = writerFolderMode(isPrivate)

    /**
     * Replaces the file [name] with [text] as UTF-8, creating it and the missing folders on the way. By default a new
     * file holding [text] is renamed over it, `.<name>.<id>.tmp` beside it until then, so a reader sees the old content
     * or the new and never part of it, and other hard links keep the old content. The new file gets the old one's
     * permissions and group.
     *
     * A [Leftover.Backup] of this file beside it, which a killed in-place write leaves, stops the write until you
     * [restore] or [discard] it, so its old content is never lost to a write that does not know of it: one listing of
     * the folder per write, and a folder that cannot be listed lets the write go ahead. Temps and partial backups never
     * stop it, and it leaves them where they are.
     *
     * Two writes of one file at the same time race, and the last rename wins. While the first is an in-place write
     * still running, its backup sits beside the file, so the second may answer [FileError.PendingBackup].
     *
     * @param inPlace writes into the file itself, keeping its inode, owner and hard links, as [WriteMode.InPlace]
     *   describes: a backup `.<name>.<id>.bak` is made first and put back when the write fails. A symlink at [name]
     *   that [replaceLink] lets go is still replaced by rename.
     * @param executable gives the file an execute bit for each class that may read it.
     * @param replaceLink puts the new file in place of a symlink at [name], which is otherwise refused. The link goes
     *   as a link, and what it leads to, inside the root or anywhere else, is never written.
     * @param modifiedAt gives the file this modification time: the new file gets it before it takes the name, and an
     *   in-place write sets it once the content is written. Null leaves the time the write gives it.
     * @return the [Written] way, [WriteWay.Renamed] or [WriteWay.InPlace], with the info of the file written;
     *   [FileError.NotRegularFile] when a folder, a symlink without [replaceLink] or anything else that is no file is
     *   at [name], with nothing touched; [FileError.PendingBackup] when a backup of this file is beside it, with
     *   nothing written; [FileError.NameTooLong] for an in-place write of a name too long for its backup; or why the
     *   folders or the file could not be made or written.
     */
    public fun writeText(
        name: String,
        text: String,
        inPlace: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError> =
        writeBytes(name, text.encodeToByteArray(), inPlace, executable, replaceLink, modifiedAt)

    /** [writeText] for raw bytes, with the same rules and answers. */
    public fun writeBytes(
        name: String,
        bytes: ByteArray,
        inPlace: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError> =
        write(name, inPlace, executable, replaceLink, kept = null, modifiedAt) { it.write(bytes) }

    /**
     * Copies the file at [source], a path of your choosing that may lie anywhere, to [name] below the root, as
     * [writeText] writes it. The content is streamed, never held in memory whole.
     *
     * @param keepAttributes gives the copy [source]'s modification time and, unless the writer is private, its
     *   permission bits within `755`: no write for the group and everyone else, and no setuid, setgid or sticky bit.
     * @param executable gives the copy an execute bit for each class that may read it.
     * @param replaceLink puts the copy in place of a symlink at [name], as [writeText] takes it.
     * @param modifiedAt gives the copy this modification time before it takes the name, in place of the one
     *   [keepAttributes] carries over.
     * @return [Written] with [WriteWay.Renamed]; [FileError.NotFound], [FileError.NotRegularFile] or
     *   [FileError.Inaccessible] naming [source] when it is no file this process may read, before anything below the
     *   root is touched; or what [writeText] answers.
     */
    public fun copyFrom(
        source: Path,
        name: String,
        keepAttributes: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError> {
        refusedToMake(name)?.let { return it }
        val found = source.metadata().getOrElse { return Err(it) }
        if (!found.isRegularFile) return Err(FileError.NotRegularFile(source, source.typeOf(found)))
        val kept = if (keepAttributes) source.keptAttributes().getOrElse { return Err(it) } else null
        val opened: RawSource = source.openSource().getOrElse { return Err(it) }
        return try {
            write(name, inPlace = false, executable, replaceLink, kept, modifiedAt) { it.transferFrom(opened) }
        } finally {
            opened.closeQuietly()
        }
    }

    override fun readText(
        name: String,
        maxBytes: Long?,
        trusted: Boolean,
        streams: Boolean,
    ): Result<String, FileError> = readBytes(name, maxBytes, trusted, streams).map { it.decodeToString() }

    override fun readBytes(
        name: String,
        maxBytes: Long?,
        trusted: Boolean,
        streams: Boolean,
    ): Result<ByteArray, FileError> = read(name, trusted, streams) { path, opened ->
        // Asked of the open file first, so a file far too large is refused by its size, not read up to the limit.
        val size = if (maxBytes == null) null else opened.sizeOrNull()
        if (maxBytes != null && size != null && size > maxBytes) {
            opened.closeQuietly()
            Err(FileError.TooLarge(path, maxBytes, size))
        } else {
            path.readFrom(opened, Read.Whole(maxBytes))
        }
    }

    override fun readPrefix(
        name: String,
        maxBytes: Long,
    ): Result<ByteArray, FileError> = read(name, trusted = false) { path, opened ->
        path.readFrom(opened, Read.Prefix(maxBytes.coerceAtLeast(0)))
    }

    override fun readSymlink(name: String): Result<Path, FileError> = named(name) { session ->
        val spot = session.walk(name, LastName.Entry, makeFolders = false).getOrElse { return@named Err(it) }
        val path = root / name
        when (val there = spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) }) {
            FileType.Symlink -> Unit
            null -> return@named Err(FileError.NotFound(path))
            else -> return@named Err(FileError.NotASymlink(path, there))
        }
        try {
            Ok(spot.level.readLink(spot.name))
        } catch (e: Exception) {
            Err(e.lookUpError(path))
        }
    }

    override fun realName(name: String): Result<String?, FileError> = named(name) { session ->
        val spot = session.walk(name, LastName.Followed, makeFolders = false, trusted = isPrivate)
            .getOrElse { return@named Err(it) }
        val path = root / name
        if (spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) } == null) {
            return@named Err(FileError.NotFound(path))
        }
        // A child writer follows a link up to its boundary, which can be a place no name below the root reaches.
        val real = spot.level.pathOf(spot.name).namedEntry()
        val below = real.relativeTo(root) ?: return@named Err(FileError.Escapes(path, boundary, real))
        Ok(below.writerName().ifEmpty { null })
    }

    override fun list(folder: String?): Result<List<String>, FileError> {
        if (folder == null) return inSession(root) { session -> namesIn(session.rootLevel, folder = null) }
        return named(folder) { session ->
            val level = session.folder(folder, makeFolders = false).getOrElse { return@named Err(it) }
            namesIn(level, folder)
        }
    }

    /** The names in [level], the folder [list] reached for [folder], in name order. */
    private fun namesIn(
        level: Level,
        folder: String?,
    ): Result<List<String>, FileError> = try {
        val names = level.list().sorted()
        Ok(if (folder == null) names else names.map { "$folder/$it" })
    } catch (e: Exception) {
        Err(inaccessible(folder?.let { root / it } ?: root, e))
    }

    override fun info(
        name: String,
        followLinks: Boolean,
    ): Result<FileInfo, FileError> = named(name) { session ->
        val last = if (followLinks) LastName.Followed else LastName.Entry
        val spot = session.walk(name, last, makeFolders = false).getOrElse { return@named Err(it) }
        val path = root / name
        try {
            Ok(spot.level.infoOf(spot.name))
        } catch (e: Exception) {
            Err(e.lookUpError(path))
        }
    }

    /**
     * Opens the file [name] for appending, creating it and the missing folders on the way, as [openAppend] on a path
     * does. A private writer makes the file `600`, or narrows one already there to it. A backup of the file beside it
     * does not stop an append, which never replaces what the file holds.
     *
     * @param reopen with [Reopen.WhenMoved], each reopen goes through this writer again, under the same rules, so a
     *   reopen that finds a symlink at [name] without [replaceLink] is that append's error, and nothing is appended.
     * @param replaceLink puts a new empty file in place of a symlink at [name], which is otherwise refused. What the
     *   link leads to is never appended to.
     * @return the open file; [FileError.NotRegularFile] when a folder, a symlink without [replaceLink] or anything else
     *   that is no file is at [name]; or why it could not be opened or made.
     */
    public fun openAppend(
        name: String,
        reopen: Reopen = Reopen.Never,
        replaceLink: Boolean = false,
    ): Result<AppendFile, FileError> = when (reopen) {
        Reopen.Never -> openAppendOnce(name, replaceLink)
        is Reopen.WhenMoved -> openAppendOnce(name, replaceLink).map { opened ->
            shownAppend(ReopeningAppendFile(opened, reopen, TimeSource.Monotonic) { openAppendOnce(name, replaceLink) })
        }
    }

    /** [file] as this writer's errors name its paths, as [shown] names them. */
    private fun shownAppend(file: AppendFile): AppendFile =
        if (placedBoundary == null) file else ShownAppendFile(file, ::shown)

    private fun openAppendOnce(
        name: String,
        replaceLink: Boolean,
    ): Result<AppendFile, FileError> = named(name) { session ->
        session.refusedUnlessThere(name)?.let { return@named it }
        val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
            .getOrElse { return@named Err(it) }
        replaceableFile(spot, root / name, replaceLink)?.let { return@named Err(it) }
        spot.level
            .openAppend(spot.name, OWNER_READ_WRITE.takeIf { isPrivate }, SHARED_FILE_MODE)
            .map(::shownAppend)
    }

    /**
     * Removes the file, symlink or empty folder [name]. A link goes as a link, whatever it leads to.
     *
     * @return `Ok` once it is gone; [FileError.NotFound] when nothing is there; [FileError.WriteFailed] when it could
     *   not be removed, such as a folder that still holds entries.
     */
    public fun delete(name: String): EmptyResult<FileError> = named(name) { session ->
        val spot = session.walk(name, LastName.Entry, makeFolders = false).getOrElse { return@named Err(it) }
        val path = root / name
        val there = try {
            spot.level.typeOf(spot.name) ?: return@named Err(FileError.NotFound(path))
        } catch (e: Exception) {
            return@named Err(inaccessible(path, e))
        }
        try {
            spot.level.remove(spot.name, folder = there == FileType.Directory)
            Ok(Unit)
        } catch (e: Exception) {
            Err(e.removalError(path))
        }
    }

    /**
     * Removes [name] and, when it is a folder, everything below it, like `rm -rf`. Every entry goes as it is, so a
     * symlink is removed as a link and what it leads to stays. Nothing at [name] is `Ok`.
     *
     * @return `Ok` once it is gone; the first failure otherwise, which can leave part of the tree behind.
     */
    public fun deleteRecursively(name: String): EmptyResult<FileError> = named(name) { session ->
        val spot = when (val walked = session.walk(name, LastName.Entry, makeFolders = false)) {
            is Result.Success -> walked.value
            is Result.Error -> return@named if (walked.error is FileError.NotFound) Ok(Unit) else walked
        }
        removeTree(spot.level, spot.name, root / name)
    }

    /** Removes each entry in the root as [deleteRecursively] removes it, whatever its name, and leaves the root. */
    internal fun clearRoot(): EmptyResult<FileError> = inSession(root) { session ->
        val level = session.rootLevel
        val entries = try {
            level.list()
        } catch (e: Exception) {
            return@inSession Err(inaccessible(root, e))
        }
        for (entry in entries) removeTree(level, entry, root / entry).onError { return@inSession Err(it) }
        Ok(Unit)
    }

    private fun removeTree(
        level: Level,
        name: String,
        path: Path,
        looks: Int = LOOKS_PER_NAME,
    ): EmptyResult<FileError> {
        val there = try {
            level.typeOf(name) ?: return Ok(Unit)
        } catch (e: Exception) {
            return Err(inaccessible(path, e))
        }
        if (there == FileType.Directory) {
            val folder = try {
                level.child(name)
            } catch (e: LevelMoved) {
                // Replaced by something else since it was looked at, which is removed as what it is now.
                if (looks > 1) return removeTree(level, name, path, looks - 1)
                return Err(FileError.Inaccessible(path, IoCause.Other, e))
            } catch (e: Exception) {
                return Err(inaccessible(path, e))
            }
            folder.use {
                val entries = try {
                    it.list()
                } catch (e: Exception) {
                    return Err(inaccessible(path, e))
                }
                for (entry in entries) {
                    val removed = removeTree(it, entry, path / entry)
                    if (removed is Result.Error) return removed
                }
            }
        }
        return try {
            level.remove(name, folder = there == FileType.Directory)
            Ok(Unit)
        } catch (e: Exception) {
            if (e.ioCause() == IoCause.NotFound) Ok(Unit) else Err(e.removalError(path))
        }
    }

    /**
     * Moves the file, folder or symlink [name] to [toName], both below the root, in one step, making the missing
     * folders on the way to [toName]. A link moves as a link, and a link at [toName] is replaced as one; a file at
     * [toName] is replaced too. A file or link moved onto a folder is [FileError.NotRegularFile] naming [toName], and a
     * folder moved onto anything but a folder is [FileError.NotADirectory].
     *
     * @return `Ok` once moved; [FileError.NotFound] when nothing is at [name], before any folder is made; or
     *   [FileError.WriteFailed] naming [toName] when the move itself failed.
     */
    public fun move(
        name: String,
        toName: String,
    ): EmptyResult<FileError> {
        refusedToMake(toName)?.let { return it }
        return named(name) { session ->
            val from = session.walk(name, LastName.Entry, makeFolders = false).getOrElse { return@named Err(it) }
            val moving = try {
                from.level.typeOf(from.name) ?: return@named Err(FileError.NotFound(root / name))
            } catch (e: Exception) {
                return@named Err(inaccessible(root / name, e))
            }
            val destination = root / toName
            val to = session.walk(toName, LastName.Entry, makeFolders = true, folderMode = folderMode)
                .getOrElse { return@named Err(it) }
            val there = try {
                to.level.typeOf(to.name)
            } catch (e: Exception) {
                return@named Err(inaccessible(destination, e))
            }
            moveRefusal(moving, there, destination)?.let { return@named Err(it) }
            try {
                from.level.move(from.name, to.level, to.name)
                Ok(Unit)
            } catch (e: Exception) {
                Err(writeFailed(destination, e))
            }
        }
    }

    /**
     * Makes the folder [name] and every missing level on the way, like `mkdir -p`; a private writer makes each `700`.
     * A folder already there, or a symlink to one below the root, is `Ok`.
     *
     * @return `Ok`; [FileError.NotADirectory] naming a file or anything else standing where a folder should be; or
     *   [FileError.WriteFailed] when a level could not be made.
     */
    public fun createDirectories(name: String): EmptyResult<FileError> = named(name, makes = true) { session ->
        session.folder(name, makeFolders = true, folderMode = folderMode).map { }
    }

    /**
     * Points the symlink [name] at [target] in one step, so a reader following it finds the old target or the new and
     * never no link, as [switchSymlinkTo] does. With nothing at [name], the link is made there, and the missing folders
     * on the way too. A relative [target] is read from the link's folder, and it must stay below the root, as a link
     * a writer follows must: by its names alone, it may not step above the root at any point, even to come back in.
     * It need not exist yet. Only its names are judged, so a [target] that is itself a link leading out is switched
     * to, and a read through the new link is [FileError.Escapes].
     *
     * ```kotlin
     * data.switchSymlink("current", Path("releases/43")).onError { println("still on the old release: $it") }
     * ```
     *
     * @return `Ok`; [FileError.Escapes] for a [target] that steps out of the root, or an absolute one not below it,
     *   and [FileError.InvalidName] with [NameProblem.LinksToItself] for a [target] naming the link itself, both with
     *   nothing made, no folder on the way either; [FileError.NotRegularFile] for a folder at [name] and
     *   [FileError.AlreadyExists] for anything else there that is no link; [FileError.NameTooLong] naming
     *   [LongName.StagedLink] for a name over [MAX_STAGED_NAME_BYTES], too long for the new link's; or
     *   [FileError.WriteFailed] when the link could not be made or moved into place, with the new link as its
     *   [FailedAt.Temp].
     */
    public fun switchSymlink(
        name: String,
        target: Path,
    ): EmptyResult<FileError> = switchSymlinkWith(name, target) { folder, staged, onto ->
        folder.move(staged, folder, onto)
    }

    /** [switchSymlink], with the new link [moveIntoPlace] in its folder, which throws why it could not. */
    internal fun switchSymlinkWith(
        name: String,
        target: Path,
        moveIntoPlace: (folder: Level, staged: String, onto: String) -> Unit,
    ): EmptyResult<FileError> {
        target.refusedName()?.let { return it }
        return named(name, makes = true) { session ->
            val path = root / name
            val planned = session.plannedEntry(name).getOrElse { return@named Err(it) }
            refusedTarget(planned, path, target)?.let { return@named Err(it) }
            val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
                .getOrElse { return@named Err(it) }
            // Judged again where the walk landed, since a folder on the way may have changed after the plan.
            refusedTarget(spot.level.pathOf(spot.name), path, target)?.let { return@named Err(it) }
            when (val there = spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) }) {
                null, FileType.Symlink -> Unit
                FileType.Directory -> return@named Err(FileError.NotRegularFile(path, FileType.Directory))
                else -> return@named Err(FileError.AlreadyExists(path, there))
            }
            val staged = spot.level.pathOf(spot.name).besideWith(STAGED_LINK_SUFFIX)
            path.tooLongToStage(staged, LongName.StagedLink)?.let { return@named it }
            try {
                spot.level.createLink(staged.name, target)
            } catch (e: Exception) {
                return@named Err(writeFailed(path, e, at = FailedAt.Temp(staged)))
            }
            try {
                moveIntoPlace(spot.level, staged.name, spot.name)
            } catch (e: Exception) {
                spot.level.removeQuietly(staged.name, folder = false)
                return@named Err(writeFailed(path, e, at = FailedAt.Temp(staged)))
            }
            val _ = spot.level.sync()
            Ok(Unit)
        }
    }

    /**
     * Tries once for the lock [withLock] takes on the file [name], without waiting, and hands it over to hold until
     * [FileLock.close], as [tryLock] on a path does. The lock file and the missing folders on the way are made; a
     * symlink at [name] is replaced by a new lock file. A private writer makes the lock file `600`.
     *
     * @return the held lock; [FileError.LockBusy] with a `waited` of zero when another holder has it;
     *   [FileError.NotRegularFile] for a folder or anything else that is no file at [name]; [FileError.LockFailed] when
     *   it could not be attempted.
     */
    public fun tryLock(name: String): Result<FileLock, FileError> = named(name) { session ->
        session.refusedUnlessThere(name)?.let { return@named it }
        val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
            .getOrElse { return@named Err(it) }
        replaceableFile(spot, root / name, replaceLink = true)?.let { return@named Err(it) }
        lockIn(spot, ::lockFailed)
    }

    /** One try at the lock on the file at [spot], which is a regular file or nothing. */
    internal fun lockIn(
        spot: Spot,
        unopened: (lockFile: Path, failure: Exception) -> FileError,
    ): Result<FileLock, FileError> {
        val lockFile = spot.level.pathOf(spot.name)
        // The real path the lock file has, which is the gate a lock on the same file by its path takes too.
        return lockFile.attemptLock(gate = lockFile.toString(), unopened, ::shown) {
            spot.level.tryLock(spot.name, OWNER_READ_WRITE.takeIf { isPrivate }, LOCK_FILE_MODE)
        }
    }

    /**
     * Runs [block] holding the lock on the file [name], as [withLock] on a path does, waiting up to [wait] for another
     * holder. The lock file is made as [tryLock] makes it.
     *
     * ```kotlin
     * state.withLock(".sync.lock") { syncNow() }.onError { println("not synced: $it") }
     * ```
     *
     * @return what [block] returned; [FileError.LockBusy] when the lock was not free in time; or what [tryLock]
     *   answers.
     */
    public inline fun <T> withLock(
        name: String,
        wait: Duration = DEFAULT_LOCK_WAIT,
        block: () -> T,
    ): Result<T, FileError> = acquireLock(name, wait).map { held -> held.use { block() } }

    /** Takes the lock [withLock] holds on [name], waiting up to [wait] for another holder. */
    @OptIn(InternalKernApi::class)
    @PublishedApi
    internal fun acquireLock(
        name: String,
        wait: Duration,
    ): Result<FileLock, FileError> =
        retryWhileLockBusy(wait, pause = { sleepMillis(it.inWholeMilliseconds) }) { tryLock(name) }

    /**
     * A shared writer on the folder [name] below the root, made with the missing levels on the way as
     * [createDirectories] makes them, so `700` under a private writer. It keeps the boundary of the writer it was made
     * from, for a stage's child the stage: a link in it may lead up as far as that boundary, and no further.
     *
     * @return the writer, its [root] the folder's real path; [FileError.NotADirectory] when something else is there.
     */
    public fun writer(name: String): Result<Writer, FileError> = child(name, childRule = null)

    /**
     * A private writer on the folder [name] below the root, as [writer] makes it, but with the folder itself made `700`
     * and refused as [privateWriter] refuses one. The missing levels on the way are made as this writer makes folders.
     *
     * ```kotlin
     * val keys = data.privateWriter("accounts/alice/keys").getOrElse { return warn(it) }
     * ```
     */
    public fun privateWriter(name: String): Result<Writer, FileError> = child(name, rootRule ?: RootRule.Private)

    private fun child(
        name: String,
        childRule: RootRule?,
    ): Result<Writer, FileError> = named(name, makes = true) { session ->
        val rootMode = writerFolderMode(childRule != null || isPrivate)
        val folder = session.folder(name, makeFolders = true, folderMode, rootMode).getOrElse { return@named Err(it) }
        try {
            val writer = Writer(folder.path, boundary, childRule, folder.key(), boundaryKey, placedBoundary)
            writer.refusedRoot(folder, asked = root / name)?.let { return@named Err(it) }
            Ok(writer)
        } catch (e: Exception) {
            Err(inaccessible(folder.path, e))
        }
    }

    /** `Ok` while the root is still the folder this writer was made for, as every call checks first. */
    internal fun checkRoot(): EmptyResult<FileError> = inSession(root) { Ok(Unit) }

    /** What is at [name] itself, a link being [FileType.Symlink], or null when nothing is there or on the way to it. */
    internal fun typeAt(name: String): Result<FileType?, FileError> = named(name) { session ->
        when (val walked = session.walk(name, LastName.Entry, makeFolders = false)) {
            is Result.Success -> walked.value.level.typeOrError(walked.value.name, root / name)
            is Result.Error -> if (walked.error is FileError.NotFound) Ok(null) else walked
        }
    }

    /**
     * Writes [name] as [writeBytes] describes, [content] putting what goes in, [kept] carried over from a copy, or else
     * [modifiedAt] given to the new file.
     */
    private fun write(
        name: String,
        inPlace: Boolean,
        executable: Boolean,
        replaceLink: Boolean,
        kept: KeptAttributes?,
        modifiedAt: Instant?,
        content: (Sink) -> Unit,
    ): Result<Written, FileError> = named(name, makes = true) { session ->
        val spot = session.walk(name, LastName.Entry, makeFolders = true, folderMode = folderMode)
            .getOrElse { return@named Err(it) }
        val path = root / name
        val there = spot.level.typeOrError(spot.name, path).getOrElse { return@named Err(it) }
        refusedAtName(there, path, replaceLink)?.let { return@named Err(it) }
        val pending = spot.level.pendingBackup(spot.name, path) { path.linkToCopies(followsName = false) }
        pending?.let { return@named Err(it) }
        val existed = there == FileType.Regular
        val plan = WritePlan(
            path = path,
            folder = spot.level,
            name = spot.name,
            content = content,
            mode = WriteMode.Replace,
            ownerOnly = isPrivate,
            permissions = if (isPrivate) OWNER_READ_WRITE else kept?.permissions?.and(SHARED_FOLDER_MODE),
            executable = executable,
            modified = modifiedTimeFor(modifiedAt, kept),
            newFileMode = SHARED_FILE_MODE,
        )
        if (inPlace && there != FileType.Symlink) {
            if (existed) {
                val partial = spot.level.pathOf(spot.name).besideWith(PARTIAL_BACKUP_SUFFIX)
                path.tooLongBeside(partial, LongName.PartialBackup)?.let { return@named it }
            }
            plan.inPlace(existed)
        } else {
            plan.byRename(existed)
        }
    }

    /** Opens the file [name] as [readText] reads it, and hands it to [readOpened] with the path it was asked by. */
    private fun <T> read(
        name: String,
        trusted: Boolean,
        streams: Boolean = false,
        readOpened: (path: Path, opened: FileSource) -> Result<T, FileError>,
    ): Result<T, FileError> = named(name) { session ->
        val checked = trusted || isPrivate
        val spot = session.walk(name, LastName.Followed, makeFolders = false, trusted = checked)
            .getOrElse { return@named Err(it) }
        val path = root / name
        val opened = try {
            spot.level.openSource(spot.name, streams)
        } catch (e: NotRegularFileFailure) {
            return@named Err(FileError.NotRegularFile(path, e.type))
        } catch (e: Exception) {
            return@named Err(e.lookUpError(path))
        }
        val file = spot.level.pathOf(spot.name)
        val refusal = if (checked) path.refusalOf(opened, trusted, accept = null, isPrivate, file) else null
        if (refusal != null) {
            opened.closeQuietly()
            return@named Err(refusal)
        }
        readOpened(path, opened)
    }

    /**
     * [FileError.InvalidRelativePath] when [name] is no relative path below the root, or null. A leftover's name
     * passes, as a call that only reads or removes what is there takes it.
     */
    private fun refused(name: String): Err<FileError>? = name
        .relativePathProblem { _, each -> each.plainNameProblem() }
        ?.let { Err(shown(FileError.InvalidRelativePath(root, name, it))) }

    /** [refused], refusing a leftover's name too, for a call that makes an entry at [name]. */
    private fun refusedToMake(name: String): Err<FileError>? =
        name.relativePathProblem()?.let { Err(shown(FileError.InvalidRelativePath(root, name, it))) }

    /**
     * Runs [act] in a [WriterSession] on this writer's folders, once [name] passed [refused], or [refusedToMake] when
     * the call [makes] an entry there, and the root was found to be the folder this writer was made for, as every call
     * does first.
     */
    internal inline fun <T> named(
        name: String,
        makes: Boolean = false,
        act: (WriterSession) -> Result<T, FileError>,
    ): Result<T, FileError> {
        (if (makes) refusedToMake(name) else refused(name))?.let { return it }
        return inSession(root / name, act)
    }

    /** [refusedToMake] for [name], unless a regular file is already there, which a call opening it does not make. */
    private fun WriterSession.refusedUnlessThere(name: String): Err<FileError>? {
        val refusal = refusedToMake(name) ?: return null
        val spot = walk(name, LastName.Entry, makeFolders = false).getOrNull() ?: return refusal
        return refusal.takeUnless { spot.level.typeOrError(spot.name, root / name).getOrNull() == FileType.Regular }
    }

    /**
     * Runs [act] in a [WriterSession] on this writer's folders, once the root proved to be the one it was made for, for
     * a call given [asked].
     */
    internal inline fun <T> inSession(
        asked: Path,
        act: (WriterSession) -> Result<T, FileError>,
    ): Result<T, FileError> {
        val done = openSession(asked).getOrElse { return Err(shown(it)) }.use(act)
        return if (done is Result.Error) Err(shown(done.error)) else done
    }

    /** [error] as this writer answers it: for a stage's writer, naming each path in the stage as it will be placed. */
    internal fun shown(error: FileError): FileError = placedBoundary?.let { error.placedAs(boundary, it) } ?: error

    /** [path] as this writer's errors name it, as [shown] names it. */
    internal fun shownPath(path: Path): Path = placedBoundary?.let { path.movedTo(boundary, it) } ?: path

    /**
     * The folders from the boundary down to the root, opened and checked against what this writer recorded, for a call
     * given [asked].
     */
    private fun openSession(asked: Path): Result<WriterSession, FileError> {
        val levels = mutableListOf<Level>()
        val failure = try {
            levels += openLevel(boundary)
            if (boundaryKey != null && levels.last().key() != boundaryKey) throw LevelMoved(boundary)
            for (name in root.segments().drop(boundary.segments().size)) levels += levels.last().child(name)
            if (rootKey != null && levels.last().key() != rootKey) throw LevelMoved(root)
            refusedRoot(levels.last(), asked)
        } catch (_: LevelMoved) {
            FileError.FolderReplaced(root)
        } catch (e: Exception) {
            inaccessible(root, e)
        }
        val session = WriterSession(this, levels)
        if (failure == null) return Ok(session)
        session.close()
        return Err(failure)
    }

    /**
     * [FileError.Refused] for [level], this writer's root, when a private writer may not keep to it, for a call given
     * [asked]; or null.
     */
    internal fun refusedRoot(
        level: Level,
        asked: Path,
    ): FileError? = when (rootRule) {
        null -> null
        RootRule.Private -> level.info().unprivateAt(level.path, asked)
        RootRule.Trusted -> level.untrusted(asked)
    }

    /**
     * [FileError.Escapes] naming [path] when [target], for the link whose real path is [link], leads out of the
     * boundary by its names, [FileError.InvalidName] when it names that link itself, or null.
     */
    private fun refusedTarget(
        link: Path,
        path: Path,
        target: Path,
    ): FileError? {
        val leadsTo = linkedNamesBelow(boundary, link.parent ?: link, target)
            ?: return FileError.Escapes(path, boundary, linkedPath(link, target).byNames())
        val itself = link.segments().drop(boundary.segments().size)
        return if (leadsTo == itself) FileError.InvalidName(path, NameProblem.LinksToItself) else null
    }

    /**
     * The names of [path] below the root, joined by `/`, judged by the real path of the folder it is in, so a path
     * spelled through a link to a folder below the root is one; [FileError.Escapes] when it lies elsewhere.
     */
    internal fun namesBelowRoot(path: Path): Result<String, FileError> {
        val folder = path.parent ?: path
        val realFolder = folder.canonicalPath().getOrNull()
        val real = if (realFolder != null && path.parent != null) Path(realFolder, path.name) else path
        val below = real.relativeTo(root)?.writerName()?.takeIf { it.isNotEmpty() }
        if (below != null) return Ok(below)
        return Err(shown(FileError.Escapes(path, boundary, realFolder ?: folder)))
    }
}

/** The size of the file open here, or null when it cannot be looked up, which the read then answers for. */
private fun FileSource.sizeOrNull(): Long? = try {
    info().size
} catch (_: Exception) {
    null
}

/**
 * Removes the entry at [name] itself, a link to nothing or an empty [folder] included, unlike [deleteQuietly], and
 * passes over a failure.
 */
internal fun Level.removeQuietly(
    name: String,
    folder: Boolean,
) {
    try {
        remove(name, folder)
    } catch (_: Exception) {
        // A staged entry left behind is a leftover, found as one, and changes no outcome already decided.
    }
}

/** The mode a writer, private when [isPrivate], makes each folder with. */
private fun writerFolderMode(isPrivate: Boolean): Int = if (isPrivate) OWNER_ONLY_FOLDER else SHARED_FOLDER_MODE

/** [FileError.Refused] for this folder when someone else may change it, for a call given [asked]; or null. */
internal fun Level.untrusted(asked: Path): FileError? = info().untrustedAt(path, asked)

/** What is at [name] itself, null for nothing, or why it could not be looked up, naming [path]. */
internal fun Level.typeOrError(
    name: String,
    path: Path,
): Result<FileType?, FileError> = try {
    Ok(typeOf(name))
} catch (e: Exception) {
    Err(inaccessible(path, e))
}

/**
 * Makes way for a file at [spot], named [path]: a symlink there is removed when [replaceLink] says so, so the file is
 * made in its place, and anything else that is no regular file is [FileError.NotRegularFile]; null once the way is
 * clear.
 */
private fun replaceableFile(
    spot: Spot,
    path: Path,
    replaceLink: Boolean,
): FileError? {
    val there = spot.level.typeOrError(spot.name, path).getOrElse { return it }
    refusedAtName(there, path, replaceLink)?.let { return it }
    if (there != FileType.Symlink) return null
    return try {
        spot.level.remove(spot.name, folder = false)
        null
    } catch (e: Exception) {
        writeFailed(path, e)
    }
}

/**
 * [FileError.NotRegularFile] naming [path] for what is [there] at a name a file is about to take, unless it is nothing,
 * a regular file, or a symlink [replaceLink] lets go; null otherwise.
 */
private fun refusedAtName(
    there: FileType?,
    path: Path,
    replaceLink: Boolean,
): FileError? {
    val takes = there == null || there == FileType.Regular || there == FileType.Symlink && replaceLink
    return if (takes) null else FileError.NotRegularFile(path, checkNotNull(there))
}

/** [FileError.NotFound] naming [path] when this failure says nothing is there, otherwise [FileError.Inaccessible]. */
internal fun Exception.lookUpError(path: Path): FileError =
    if (ioCause() == IoCause.NotFound) FileError.NotFound(path) else inaccessible(path, this)

/** [FileError.NotFound] naming [path] when this failure says nothing was there, otherwise [FileError.WriteFailed]. */
internal fun Exception.removalError(path: Path): FileError =
    if (ioCause() == IoCause.NotFound) FileError.NotFound(path) else writeFailed(path, this)
