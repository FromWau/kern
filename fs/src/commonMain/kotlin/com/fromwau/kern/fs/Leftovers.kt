package com.fromwau.kern.fs

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlinx.io.files.Path
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A copy a write left at [copy], beside the file it was writing, which a write disposes of itself when it can. One
 * left behind means a process was killed during a write, or a write could not finish cleaning up, and marks that file
 * as worth checking rather than proving it wrong. Each kind but [UnnamedTemp] names the file as its `file`.
 *
 * ```kotlin
 * val config = dirs.config.writer().getOrElse { return warn(it) }
 * for (leftover in config.leftovers().getOrElse { return warn(it) }.found) {
 *     when (leftover) {
 *         is Leftover.Backup -> println("${leftover.file} may be cut short; its old content is in ${leftover.copy}")
 *         is Leftover.PartialBackup, is Leftover.Temp, is Leftover.UnnamedTemp, is Leftover.StagedFolder,
 *         is Leftover.StagedLink -> config.discard(leftover).onError { println("not discarded: $it") }
 *     }
 * }
 * ```
 *
 * A leftover is only listed here. [Writer.discard] removes one and [Writer.restore] puts a backup back, each through
 * the [Writer] of the folder it lies in.
 *
 * Only a copy a write made counts: each is named `.<name>.<id>.tmp`, `.<name>.<id>.bak`, `.<name>.<id>.bak.part`,
 * for a folder [createDirectoryAtomically] was building `.<name>.<id>.dir`, or for a link [switchSymlinkTo] was
 * switching `.<name>.<id>.link`, where `<id>` is the time-ordered UUID of its write, so a copy someone kept beside the
 * file under a name of their own is never one of these. A rename write of a name too long for `.<name>.<id>.tmp`
 * writes `.<id>.tmp` instead.
 */
public sealed interface Leftover {
    /** The leftover copy itself. */
    public val copy: Path

    /** The file the write was writing, whether or not it exists now; null for an [UnnamedTemp], which names none. */
    public val file: Path?

    /**
     * When the write that left [copy] started, to the millisecond, as the time-ordered id in the copy's name records
     * it. Null when that id is not one: a UUID of another version, as a copy someone named by hand can carry.
     */
    public val startedAt: Instant?

    /**
     * The symlink that leads to the folder [copy] lies in: a link to a file at the name a scan, a write or a
     * [leftovers] call was given, or else the folder that name sits in when that folder is a link. For a copy directly
     * in a folder a [leftoversIn] scan was given, that is the folder itself when it is a link. Null otherwise, also for
     * a link further up the way; a [Writer] write never goes through a link at the name, so only its folder counts.
     */
    public val via: Path?

    /**
     * Whether the file this copy was for may be half-written, so it is worth checking before the copy is discarded.
     * Only a [Backup] says so; every other kind leaves the file as it was.
     */
    public val fileMayBeDamaged: Boolean

    /**
     * The whole old content of [file], copied before an in-place write emptied it. The write was then cut short or
     * could not put this back, so [file] can be half-written, though it can also read perfectly: a write cut short can
     * leave text that still parses and says less than it did.
     *
     * @property file the file the write was writing, whether or not it exists now.
     */
    public data class Backup(
        override val file: Path,
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** True: an in-place write emptied [file] before it stopped, so [file] can be cut short. */
        override val fileMayBeDamaged: Boolean get() = true
    }

    /**
     * A backup an in-place write was still copying when it stopped, so it may hold only part of [file]. [file] itself
     * was not written yet: the write empties it only once the copy is complete.
     *
     * @property file the file the write was writing, whether or not it exists now.
     */
    public data class PartialBackup(
        override val file: Path,
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** False: [file] is emptied only once its backup is complete, so it was not written yet. */
        override val fileMayBeDamaged: Boolean get() = false
    }

    /**
     * New content a rename write never renamed over [file], so [file] is as it was before that write.
     *
     * @property file the file the write was writing, whether or not it exists now.
     */
    public data class Temp(
        override val file: Path,
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** False: the new content never replaced [file], which is as it was. */
        override val fileMayBeDamaged: Boolean get() = false
    }

    /**
     * New content a rename write of a file whose name is too long for `.<name>.<id>.tmp` never renamed into place. Its
     * name, `.<id>.tmp`, does not say which file in the folder it was for, and that file is as it was before the write.
     * So [leftovers] of one file leaves it out, and [leftoversIn] finds it.
     */
    public data class UnnamedTemp(
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** Null: `.<id>.tmp` does not say which file it was for. */
        override val file: Path? get() = null

        /** False: the new content never replaced its file, which is as it was. */
        override val fileMayBeDamaged: Boolean get() = false
    }

    /**
     * The stage [createDirectoryAtomically] was building the folder [file] in when its process stopped, so [file] was
     * never put in place by it.
     *
     * @property file the folder it was building, whether or not something is there now.
     */
    public data class StagedFolder(
        override val file: Path,
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** False: the folder was never put in place, so whatever is at [file] is not this build's. */
        override val fileMayBeDamaged: Boolean get() = false
    }

    /**
     * The new link [switchSymlinkTo] made beside the link [file] and never moved into place, so [file] is as it was.
     * There is nothing to restore: [Writer.discard] removes this link and never what it leads to.
     *
     * @property file the link it was switching, whether or not something is there now.
     */
    public data class StagedLink(
        override val file: Path,
        override val copy: Path,
        override val startedAt: Instant?,
        override val via: Path? = null,
    ) : Leftover {
        /** False: the new link never replaced [file], which is as it was. */
        override val fileMayBeDamaged: Boolean get() = false
    }
}

/**
 * Every [Leftover] a write left in this folder, of any file, as [LeftoverScan.found], the oldest write first and one
 * write's copies in name order, those with no [Leftover.startedAt] last. A temp file is found for a file that does not
 * exist too, since a write that creates a file leaves one when it is cut short.
 *
 * Each symlink in the folder, also one whose target is missing, is asked for its [leftovers] too, since a write
 * through it works beside the file it leads to. What that finds outside this folder goes into
 * [LeftoverScan.throughLinks], in the same order, with the link as [Leftover.via]; a copy found both ways is listed
 * once, in [LeftoverScan.found], and one two links lead to once, through the first link. A link whose target cannot
 * be checked, such as a loop or one it may not look up, goes into [LeftoverScan.failed] and the scan goes on; one gone
 * by then is passed over. An entry whose type cannot be looked up, such as one in a folder it may read but not search,
 * goes there too, since it may be a link. A link to a folder is not entered. Nothing is deleted.
 *
 * When this folder is itself a symlink, each copy directly in it has this folder as its [Leftover.via], as [leftovers]
 * of its file gives it; a copy in a folder below has none.
 *
 * ```kotlin
 * val scan = dirs.config.leftoversIn().orIfNotFound { LeftoverScan.EMPTY }.getOrElse { return warn(it) }
 * scan.failed.forEach { println("not checked: ${it.path}: ${it.error}") }
 * (scan.found + scan.throughLinks).forEach { println("${it.file} has ${it.copy} left over") }
 * ```
 *
 * @param depth how many levels of folders below this one are read too, as one scan ordered as above, each found in
 *   [LeftoverScan.found] whichever folder it is in: 0 reads this folder alone, 1 this folder and each folder directly
 *   in it, and [Int.MAX_VALUE] every folder below. A link to a folder is never entered; one the scan would have
 *   entered were it a folder goes into [LeftoverScan.passedOver]. A folder found as a [Leftover.StagedFolder] is not
 *   entered either and is listed once with all it holds. A folder below that cannot be listed goes into
 *   [LeftoverScan.failed] and the scan goes on, and one gone by the time it is read is passed over.
 * @return what was found and what could not be checked; [FileError.NotADirectory] when this path is not a folder,
 *   [FileError.NotFound] when nothing is at it, or what listing it answers.
 * @throws IllegalArgumentException when [depth] is negative.
 */
public fun Path.leftoversIn(depth: Int = 0): Result<LeftoverScan, FileError> {
    requireDepth(depth)
    refusedPath()?.let { return it }
    return listedTree(depth).map { it.scan() }
}

/** Refuses a negative [depth], which no scan can read. */
internal fun requireDepth(depth: Int) {
    require(depth >= 0) { "depth must be 0 or more, not $depth" }
}

/**
 * Every [Leftover] a write left in this app's config, data, state and runtime folders, each read whole as
 * [leftoversIn] with `depth = Int.MAX_VALUE` reads it, as one scan. A folder that does not exist is passed over, one
 * below them that cannot be listed goes into [LeftoverScan.failed], and one folder that is two of them, such as config
 * and data on macOS, is reported once. The cache folder is not scanned; `cache.leftoversIn(depth = Int.MAX_VALUE)`
 * scans it.
 *
 * ```kotlin
 * val scan = dirs.leftovers().getOrElse { return warn(it) }
 * scan.found.forEach { println("${it.file} has ${it.copy} left over") }
 * ```
 *
 * @return what was found and what could not be checked; or what listing one of these folders answers when that is
 *   not [FileError.NotFound].
 */
public fun AppDirs.leftovers(): Result<LeftoverScan, FileError> {
    val listed = mutableListOf<ListedFolder>()
    val unlisted = mutableListOf<ScanFailure>()
    for (root in listOfNotNull(config, data, state, runtime)) {
        when (val read = root.listedTree(depth = Int.MAX_VALUE)) {
            is Result.Success -> {
                listed += read.value.folders
                unlisted += read.value.unlisted
            }
            is Result.Error -> if (read.error !is FileError.NotFound) return read
        }
    }
    val folders = listed.distinctBy { it.folder.oneName() }
    val failures = unlisted.distinctBy { it.path.oneName() }
    return Ok(ListedTree(folders, failures).scan())
}

/**
 * A folder [leftoversIn] read, with its [entries]; [entered] when the scan went on into the folders among them, so a
 * link to a folder there was passed over.
 */
private class ListedFolder(
    val folder: Path,
    val entries: List<Path>,
    val entered: Boolean,
)

/** The folders [leftoversIn] read, and those below the scanned one it could not list, as [unlisted]. */
private class ListedTree(
    val folders: List<ListedFolder>,
    val unlisted: List<ScanFailure>,
)

/**
 * This folder with its entries, and every folder [depth] levels below it with theirs, not following a link to a
 * folder and putting one below that cannot be listed into [ListedTree.unlisted]; or what listing this folder answers.
 */
private fun Path.listedTree(depth: Int): Result<ListedTree, FileError> {
    val listed = mutableListOf<ListedFolder>()
    val unlisted = mutableListOf<ScanFailure>()
    val pending = ArrayDeque(listOf(this to 0))
    while (pending.isNotEmpty()) {
        val (folder, level) = pending.removeFirst()
        val entries = when (val read = folder.list()) {
            is Result.Success -> read.value
            is Result.Error -> {
                if (folder == this) return read
                val goneSince = read.error is FileError.NotFound || read.error is FileError.NotADirectory
                if (!goneSince) unlisted += ScanFailure(folder, read.error)
                continue
            }
        }
        val entered = level < depth
        listed += ListedFolder(folder, entries, entered)
        if (entered) pending += entries.filter { it.isFolderToScan() }.map { it to level + 1 }
    }
    return Ok(ListedTree(listed, unlisted))
}

/**
 * Whether a scan that goes below a folder reads this entry: a real folder, not a link to one, and not a stage it lists
 * whole.
 */
private fun Path.isFolderToScan(): Boolean =
    !isSymlink(this) && fileType() == Ok(FileType.Directory) && asLeftover(via = null) !is Leftover.StagedFolder

/** The leftovers in these folders and those their symlinks lead to, as [leftoversIn] lists them. */
private fun ListedTree.scan(): LeftoverScan {
    val found = folders
        .flatMap { it.entries }
        .filter { it.name.isLeftoverName() }
        .mapNotNull { it.asLeftover(via = it.linkToCopies(followsName = false)) }
    val throughLinks = mutableListOf<Leftover>()
    val failed = unlisted.toMutableList()
    val passedOver = mutableListOf<Path>()
    val seen = found.mapTo(mutableSetOf()) { it.copy.oneName() }
    for (listed in folders) {
        for (entry in listed.entries) {
            val type = entry.fileType()
            if (!isSymlink(entry)) {
                // A lookup that fails leaves open whether the entry is a link, so it was not checked.
                if (type is Result.Error && type.error !is FileError.NotFound) failed += ScanFailure(entry, type.error)
                continue
            }
            if (type == Ok(FileType.Directory)) {
                if (listed.entered) passedOver += entry
                continue
            }
            when (val asked = entry.leftovers()) {
                is Result.Success -> asked.value.filterTo(throughLinks) { seen.add(it.copy.oneName()) }
                is Result.Error -> if (asked.error !is FileError.NotFound) failed += ScanFailure(entry, asked.error)
            }
        }
    }
    return LeftoverScan(found.sortedWith(OLDEST_FIRST), throughLinks.sortedWith(OLDEST_FIRST), failed, passedOver)
}

/**
 * What [leftoversIn] found in a folder: the leftovers in it as [found], those beside a file a symlink in it leads to
 * elsewhere as [throughLinks], each list ordered as [leftoversIn] orders them, and each entry in it, or folder below
 * it, that could not be checked, as [failed]. [passedOver] holds each symlink to a folder that a scan with a `depth`
 * would have entered were it a folder and did not, spelled as the scan found it, so what that folder holds was not
 * scanned; a scan of one folder leaves it empty.
 */
public data class LeftoverScan(
    val found: List<Leftover>,
    val throughLinks: List<Leftover>,
    val failed: List<ScanFailure>,
    val passedOver: List<Path>,
) {
    /**
     * The backup to restore first for each file in [byFile] that has one, by that file: the one whose write started
     * earliest, the first by name of those started in the same millisecond, and one with no [Leftover.startedAt] only
     * when the file has no other. The oldest is listed first.
     *
     * ```kotlin
     * for ((file, oldest) in scan.oldestBackups()) println("$file: restore ${oldest.copy} first")
     * ```
     *
     * @param throughLinks also takes in the backups in [LeftoverScan.throughLinks], as [byFile] takes them in, so a
     *   file reached both directly and through a link is one entry, keyed as [byFile] keys it.
     */
    public fun oldestBackups(throughLinks: Boolean = false): Map<Path, Leftover.Backup> =
        byFile(throughLinks)
            .mapNotNull { (file, leftovers) ->
                leftovers.filterIsInstance<Leftover.Backup>().firstOrNull()?.let { file to it }
            }
            .sortedWith(compareBy(OLDEST_FIRST) { (_, backup) -> backup })
            .toMap()

    /**
     * The leftovers in [found] of each file, by that file, each list in the order [leftoversIn] gives, so the oldest
     * write's copies come first. The file whose oldest leftover is oldest is listed first. A [Leftover.UnnamedTemp],
     * which names no file, is left out. Two spellings of one file are one entry, matched by [realPath] and keyed by the
     * `file` of its oldest leftover. A [Leftover.StagedFolder] or [Leftover.StagedLink] is for the folder or link at
     * its `file` itself, so it is matched by that path with only its folder resolved: a staged link and the file the
     * link leads to are two entries.
     *
     * ```kotlin
     * for ((file, leftovers) in scan.byFile()) println("$file: ${leftovers.size} left over")
     * ```
     *
     * @param throughLinks also takes in [LeftoverScan.throughLinks], which lie wherever the links lead, for an app
     *   whose files may be symlinks, so a file reached both directly and through a link is one entry.
     */
    public fun byFile(throughLinks: Boolean = false): Map<Path, List<Leftover>> {
        val groups = LinkedHashMap<Path, Pair<Path, MutableList<Leftover>>>()
        for (leftover in (if (throughLinks) found + this.throughLinks else found).sortedWith(OLDEST_FIRST)) {
            val file = leftover.file ?: continue
            groups.getOrPut(leftover.sameFileKey(file)) { file to mutableListOf() }.second += leftover
        }
        return groups.values.associate { (file, leftovers) -> file to leftovers }
    }

    public companion object {
        /**
         * A scan that found nothing and failed on nothing, such as for a folder not made yet:
         * `folder.leftoversIn().orIfNotFound { LeftoverScan.EMPTY }`.
         */
        public val EMPTY: LeftoverScan = LeftoverScan(emptyList(), emptyList(), emptyList(), emptyList())
    }
}

/**
 * What [LeftoverScan.byFile] matches [file] by: its [realPath], or for a stage, its own path with only its folder
 * resolved, since a stage is for the link or folder at [file] itself, never what a link there leads to.
 */
private fun Leftover.sameFileKey(file: Path): Path = when (this) {
    is Leftover.StagedFolder, is Leftover.StagedLink -> file.folderOrDot().oneName() / file.name
    else -> file.oneName()
}

/**
 * What [leftoversIn] could not check, for what [error] says: at [path], a symlink or an entry of unknown type in a
 * folder it read, or a folder below the scanned one that it could not list.
 */
public data class ScanFailure(
    val path: Path,
    val error: FileError,
)

/**
 * The [Leftover]s writes of this file left beside it, ordered as [leftoversIn] orders them, also when the file does not
 * exist: a write that creates the file can leave a temp file. A symlink is followed to the file it points at, since a
 * write works beside that file, also when it is missing; found that way, a leftover is spelled from the link's folder
 * and may hold `..`, which [realPath] removes. Its `file`, on every kind but [Leftover.UnnamedTemp], names the target,
 * and [Leftover.via] the link. A file in a folder that is a link has that folder as its [Leftover.via], as a
 * [leftoversIn] scan of that folder gives it. A link to a folder is no [Leftover.via], since the copies lie beside that
 * folder, where the link does not lead.
 *
 * @return the leftovers; [FileError.Inaccessible] naming this path when it cannot be looked up, such as a symlink
 *   loop, or the error reading the folder it sits in, naming that folder.
 */
public fun Path.leftovers(): Result<List<Leftover>, FileError> {
    refusedPath()?.let { return it }
    val looked = metadata()
    if (looked is Result.Error && looked.error !is FileError.NotFound) return looked
    val target = writeTarget()
    val holder = target.parent ?: return Ok(emptyList())
    val via = linkToCopies()
    return holder.list().map { entries ->
        entries
            .mapNotNull { it.asLeftover(via) }
            .filter { it.file?.name == target.name }
            .sortedWith(OLDEST_FIRST)
    }
}

/**
 * [FileError.PendingBackup] naming [path] for the oldest backup of the file [name] beside it in this folder, which a
 * write may not run past until it is restored or discarded, found by way of the link [via] answers; or null when there
 * is none.
 */
internal fun WriteFolder.pendingBackup(
    name: String,
    path: Path,
    via: () -> Path?,
): FileError.PendingBackup? {
    val entries = try {
        list()
    } catch (_: Exception) {
        // A folder that cannot be listed cannot be scanned for the backup either, so nothing could ever clear it.
        return null
    }
    val backup = entries
        .mapNotNull { pathOf(it).asLeftover(via = null) as? Leftover.Backup }
        .filter { it.file.name == name }
        .minWithOrNull(OLDEST_FIRST)
    return backup?.let { FileError.PendingBackup(path, it.copy(via = via())) }
}

/**
 * The [Leftover.via] of a copy beside the file at this path, the symlink that leads to the folder the copy lies in:
 * this path when it is a link to no folder and the write [followsName], or else the folder it sits in when that is a
 * link; null for neither. Asked of a copy itself with [followsName] false, it is the copy's own folder when a link.
 */
internal fun Path.linkToCopies(followsName: Boolean = true): Path? = when {
    followsName && isSymlink(this) && fileType() != Ok(FileType.Directory) -> this
    parent?.let(::isSymlink) == true -> parent
    else -> null
}

/** The leftover this entry is, read from its name and found by way of [via], or null when no write left it. */
internal fun Path.asLeftover(via: Path?): Leftover? {
    val parts = name.leftoverParts() ?: return null
    val startedAt = parts.id.writeStartedAt()
    val fileName = parts.fileName ?: return Leftover.UnnamedTemp(this, startedAt, via)
    val file = sibling(fileName)
    return when (parts.suffix) {
        BACKUP_SUFFIX -> Leftover.Backup(file, this, startedAt, via)
        PARTIAL_BACKUP_SUFFIX -> Leftover.PartialBackup(file, this, startedAt, via)
        STAGE_SUFFIX -> Leftover.StagedFolder(file, this, startedAt, via)
        STAGED_LINK_SUFFIX -> Leftover.StagedLink(file, this, startedAt, via)
        else -> Leftover.Temp(file, this, startedAt, via)
    }
}

/** What a leftover's name says: the [suffix] of its kind, the [fileName] it was for, null for `.<id>.tmp`, and [id]. */
private class LeftoverParts(
    val suffix: String,
    val fileName: String?,
    val id: Uuid,
)

/** This name read as `.<file>.<id><suffix>` or `.<id>.tmp`, as kern's writes name their copies, or null. */
private fun String.leftoverParts(): LeftoverParts? {
    val suffix = LEFTOVER_SUFFIXES.firstOrNull { endsWith(it) } ?: return null
    // `.<file>.<id><suffix>`, where the id has one fixed length, so the file name is whatever comes before it.
    val idStart = length - suffix.length - WRITE_ID_LENGTH
    if (!startsWith(".")) return null
    // `.<id>.tmp`: the id right after the leading dot is a temp written for no file name.
    if (idStart == 1 && suffix == TEMP_SUFFIX) {
        val id = substring(1, 1 + WRITE_ID_LENGTH).canonicalUuidOrNull() ?: return null
        return LeftoverParts(suffix, fileName = null, id)
    }
    if (idStart < MIN_ID_START || this[idStart - 1] != '.') return null
    val id = substring(idStart, idStart + WRITE_ID_LENGTH).canonicalUuidOrNull() ?: return null
    return LeftoverParts(suffix, fileName = substring(1, idStart - 1), id)
}

/** Whether this name is one kern's writes give the copies they leave, as [leftoversIn] reads names. */
internal fun String.isLeftoverName(): Boolean = leftoverParts() != null

/** The UUID this spells the way [Uuid.toString] spells one, lower case and dashed, or null. */
internal fun String.canonicalUuidOrNull(): Uuid? = Uuid.parseHexDashOrNull(this)?.takeIf { it.toString() == this }

/** A new id for a write, whose first 48 bits count the milliseconds since the epoch, as [writeStartedAt] reads them. */
@OptIn(ExperimentalUuidApi::class)
internal fun newWriteId(): Uuid = Uuid.generateV7()

/**
 * When the write this id names started: the millisecond its first 48 bits count, as [newWriteId] makes them. Null for
 * an id of another version than 7, whose first bits count nothing.
 */
private fun Uuid.writeStartedAt(): Instant? = toLongs { most, _ ->
    val version = (most ushr 12).toInt() and 0xF
    if (version == 7) Instant.fromEpochMilliseconds(most ushr 16) else null
}

/** The oldest write first, those with no time after every dated one, and copies with one time by name. */
internal val OLDEST_FIRST: Comparator<Leftover> =
    compareBy<Leftover, Instant?>(nullsLast()) { it.startedAt }.thenBy { it.copy.name }

internal const val BACKUP_SUFFIX = ".bak"

internal const val PARTIAL_BACKUP_SUFFIX = ".bak.part"

internal const val TEMP_SUFFIX = ".tmp"

internal const val STAGE_SUFFIX = ".dir"

internal const val STAGED_LINK_SUFFIX = ".link"

private val LEFTOVER_SUFFIXES =
    listOf(PARTIAL_BACKUP_SUFFIX, BACKUP_SUFFIX, TEMP_SUFFIX, STAGE_SUFFIX, STAGED_LINK_SUFFIX)

/**
 * Where each kind of [Leftover] beside a file is put, named as kern's own writes, folder builds and link switches name
 * them, each with a new write id; for kern's test helpers, which leave real leftovers to find. A write's copies go
 * beside the file a symlink at `file` leads to, as the write puts them; a stage goes beside `file` itself.
 */
@InternalKernApi
public object LeftoverNames {
    /** `.<name>.<id>.tmp` beside [file], a [Leftover.Temp]. */
    public fun temp(file: Path): Path = file.writeTarget().besideWith(TEMP_SUFFIX)

    /** `.<id>.tmp` beside [file], a [Leftover.UnnamedTemp]. */
    public fun unnamedTemp(file: Path): Path = file.writeTarget().unnamedTempBeside(newWriteId())

    /** `.<name>.<id>.bak` beside [file], a [Leftover.Backup]. */
    public fun backup(file: Path): Path = file.writeTarget().besideWith(BACKUP_SUFFIX)

    /** `.<name>.<id>.bak.part` beside [file], a [Leftover.PartialBackup]. */
    public fun partialBackup(file: Path): Path = file.writeTarget().besideWith(PARTIAL_BACKUP_SUFFIX)

    /** `.<name>.<id>.dir` beside [file], a [Leftover.StagedFolder]. */
    public fun stagedFolder(file: Path): Path = file.besideWith(STAGE_SUFFIX)

    /** `.<name>.<id>.link` beside [file], a [Leftover.StagedLink]. */
    public fun stagedLink(file: Path): Path = file.besideWith(STAGED_LINK_SUFFIX)
}

/** The length of a write id spelled the way [Uuid.toString] spells it. */
internal const val WRITE_ID_LENGTH = 36

/** Where the id can start at the earliest: after the leading dot, a one-character file name and a dot. */
private const val MIN_ID_START = 3
