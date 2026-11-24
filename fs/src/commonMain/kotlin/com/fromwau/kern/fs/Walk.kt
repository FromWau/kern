package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import kotlin.time.Instant
import kotlinx.io.files.Path

/**
 * Every entry under this path: each regular file as a [WalkEntry.File], each folder below the root the walk enters as a
 * [WalkEntry.Folder], each entry the walk passes over as a [WalkEntry.Skipped] saying why, and a [WalkFailure] for each
 * directory or entry below it that could not be read. Each directory's own entries come first, in name order, then its
 * subdirectories are walked, also in name order, each yielded as a [WalkEntry.Folder] just before its own entries, so
 * `a.txt`, `b/x` and `c.txt` walk as `a.txt`, `c.txt`, `b`, `b/x`. Name order compares code units, so `B.txt` comes
 * before `a.txt` and `.hidden` before both.
 *
 * ```kotlin
 * var total = 0L
 * for (step in root.walkTopDown(enter = { it.path.name != ".git" }).getOrElse { return warn(it) }) {
 *     val entry = step.getOrElse {
 *         warn(it.error)
 *         continue
 *     }
 *     when (entry) {
 *         is WalkEntry.File -> total += entry.size
 *         is WalkEntry.Folder, is WalkEntry.Link -> Unit
 *         is WalkEntry.Skipped -> println("passed over ${entry.relative}: ${entry.why}")
 *     }
 * }
 * ```
 *
 * The root is looked up, and a directory root listed, when this is called, so a root that cannot be walked is the
 * outer error; everything below it is read as the sequence is iterated. Iterating the sequence again walks afresh,
 * listing the root again, and a root that can no longer be listed is then the walk's one [WalkFailure]. A regular file
 * walks as itself, and a root that is a symlink is followed whatever [symlinks] says.
 *
 * [symlinks] says which symlinks below the root are followed, as [Symlinks] describes; by default those that lead to
 * a place inside the root; under [Symlinks.AsLinks] none is, and each is yielded as a [WalkEntry.Link]. A link that is
 * followed to a file is yielded as that file, unless [exclude] leaves it out, and one followed to a folder is entered
 * as that folder. An entry gone before the walk looks it up, or a folder gone before the walk lists it, is skipped as
 * [WalkSkip.Gone] instead of being yielded as a [WalkEntry.Folder]. A symlink whose links go round is skipped as
 * [WalkSkip.LinkLoop].
 *
 * Where links are followed, each link to a folder waits until every folder reached without one is walked, then they
 * are taken in the order the walk met them, so a folder inside the root is walked under its own path first. Under
 * [Symlinks.Inside], `alias -> assets` walks `assets` and what it holds, then yields `alias` as
 * [WalkSkip.AlreadyEntered] naming `assets`. A link to a folder not walked yet, such as one outside the root under
 * [Symlinks.Anywhere], is entered under its own name then, and the links to folders met below it join the end of
 * the line. [enter] is asked about such a link when its turn comes, and one into [exclude] is passed over where met.
 * A waiting link that is no link to a folder any more by its turn, such as one pointed at a file or removed, is
 * skipped as [WalkSkip.Changed] then, at that later point of the walk rather than among its folder's entries. The walk
 * holds one entry per link waiting.
 *
 * The walk looks at each entry once and never again. An entry that changes between the lookups of that one look is
 * skipped as [WalkSkip.Changed], and a folder is never listed through a link the walk did not follow: a folder swapped
 * for a link after the walk met it and before it is listed, a followed link pointed elsewhere by then, and a folder
 * whose folder above was swapped for a link are each skipped as [WalkSkip.Changed].
 *
 * @param exclude folders and files the walk leaves out, however they are reached: a folder below the root whose real
 *   path is one of them or below one is yielded as [WalkSkip.Excluded] and never read, and [enter] is not asked about
 *   it. A file whose real path is one of them, and a symlink to a file in one of them or below one, is yielded as
 *   [WalkSkip.Excluded] too, in every mode of [symlinks] that would yield it. Each is resolved as [realPath] does once,
 *   when this is called; one nothing is at excludes nothing. The root is always walked. Under [Symlinks.AsLinks] and
 *   [Symlinks.NoLinks], which follow no link, a symlink in [exclude] is resolved to its own place instead, the real
 *   path of its folder joined with its name, and a link whose own place is one of them or below one is yielded as
 *   [WalkSkip.Excluded], so what it leads to is still walked. Under the other modes it excludes what it leads to.
 * @param enter asked about each folder below the root that the walk would enter, as the [WalkEntry.Folder] it would
 *   yield, when the walk meets it among its parent's entries; a folder it answers false for is yielded as
 *   [WalkSkip.Refused] and never read, and one it accepts is yielded once the walk enters it. The root is never
 *   passed to it and is always walked. Where links are followed it is not asked about a folder already walked, and a
 *   folder it refuses counts as not walked, so a later path to the same folder is asked again. A throw from this
 *   block is not caught, and ends the iteration.
 * @return the entries; [FileError.NotFound] or [FileError.Inaccessible] when the root cannot be looked up or listed,
 *   [FileError.NotRegularFile] when it is a FIFO, device or socket, [FileError.NotADirectory] when a folder root was
 *   swapped for a file before it could be listed, or what [realPath] answers for a folder in [exclude] that is there
 *   but cannot be resolved.
 */
public fun Path.walkTopDown(
    symlinks: Symlinks = Symlinks.Inside,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
): Result<Sequence<Result<WalkEntry, WalkFailure>>, FileError> =
    walkTopDownWith(SystemLookups, symlinks, exclude, enter)

/** [walkTopDown], with each entry looked at through [lookups]. */
internal fun Path.walkTopDownWith(
    lookups: WalkLookups,
    symlinks: Symlinks = Symlinks.Inside,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
): Result<Sequence<Result<WalkEntry, WalkFailure>>, FileError> {
    val found = when (val looked = lookups.info(this)) {
        is Result.Success -> looked.value
        is Result.Error -> return looked
    }
    val excluded = when (val resolved = exclude.realPaths(lookups, linksByPlace = !symlinks.followsLinks())) {
        is Result.Success -> resolved.value
        is Result.Error -> return resolved
    }
    val rules = WalkRules(symlinks, excluded, enter, lookups)
    return when (found.type) {
        FileType.Regular -> Ok(sequenceOf(Ok(fileEntry(Path(name), found, viaLink = lookups.isLink(this)))))
        FileType.Directory -> lookups.list(this).map { entries -> walkFrom(this, entries, rules) }
        else -> Err(FileError.NotRegularFile(this, found.type))
    }
}

/**
 * The real path of each of these that something is at, or the first error resolving one gave otherwise. With
 * [linksByPlace], a symlink stands for its own place, the real path of its folder joined with its name.
 */
private fun Set<Path>.realPaths(
    lookups: WalkLookups,
    linksByPlace: Boolean,
): Result<Set<Path>, FileError> {
    val real = mutableSetOf<Path>()
    for (path in this) {
        // Refused first, so a native lookup never reads a path holding a NUL as the shorter one before it.
        val byPlace = linksByPlace && path.refusedPath() == null && lookups.isLink(path)
        val resolved = if (byPlace) lookups.ownPlace(path) else lookups.realPath(path)
        when (resolved) {
            is Result.Success -> real += resolved.value
            is Result.Error -> if (resolved.error !is FileError.NotFound) return resolved
        }
    }
    return Ok(real)
}

/** Where the symlink [link] itself is: the real path of the folder it is in, joined with its name. */
private fun WalkLookups.ownPlace(link: Path): Result<Path, FileError> =
    realPath(link.folderOrDot()).map { Path(it, link.name) }

/** Whether a walk in this mode follows a link, rather than passing it over or yielding it as one. */
private fun Symlinks.followsLinks(): Boolean = this == Symlinks.Inside || this == Symlinks.Anywhere

/** The lookups a walk makes of each entry it lists, which a test can count or make a change between. */
internal interface WalkLookups {
    /** Whether [path] is itself a symlink, as [isSymlink] answers. */
    fun isLink(path: Path): Boolean

    /** What [path] leads to, as [Path.info] reads it. */
    fun info(path: Path): Result<FileInfo, FileError.LookupError>

    /** What is at [path] itself, as [Path.info] reads it but describing a symlink there as the link. */
    fun ownInfo(path: Path): Result<FileInfo, FileError.LookupError>

    /** What the symlink [path] holds, as [readLink] reads it, throwing why it cannot be read. */
    fun linkTarget(path: Path): Path

    /** One spelling of [path], as [oneName] gives it. */
    fun oneName(path: Path): Path

    /** The real path of [path], as [Path.realPath] answers it. */
    fun realPath(path: Path): Result<Path, FileError.LookupError>

    /** The entries of the folder [path], as [Path.list] gives them. */
    fun list(path: Path): Result<List<Path>, FileError>
}

/** The lookups of the file system itself. */
internal object SystemLookups : WalkLookups {
    override fun isLink(path: Path): Boolean = isSymlink(path)

    override fun info(path: Path): Result<FileInfo, FileError.LookupError> = path.info()

    override fun ownInfo(path: Path): Result<FileInfo, FileError.LookupError> {
        path.refusedPath()?.let { return it }
        return try {
            Ok(ownFileInfoAt(path))
        } catch (e: Exception) {
            path.inaccessibleUnlessGone(e)
        }
    }

    override fun linkTarget(path: Path): Path = readLink(path)

    override fun oneName(path: Path): Path = path.oneName()

    override fun realPath(path: Path): Result<Path, FileError.LookupError> = path.realPath()

    override fun list(path: Path): Result<List<Path>, FileError> = path.list()
}

/**
 * What a walk was asked to do with links, which folders it was told to leave out and which to [enter], and the
 * [lookups] it makes of each entry.
 */
private class WalkRules(
    val symlinks: Symlinks,
    val excluded: Set<Path>,
    val enter: (WalkEntry.Folder) -> Boolean,
    val lookups: WalkLookups,
) {
    /** Why the walk does not enter [folder], whose real path is [real], or null when it does. */
    fun refusal(
        folder: WalkEntry.Folder,
        real: Lazy<Path>,
    ): WalkSkip? = when {
        excludesFolder(real) -> WalkSkip.Excluded
        !enter(folder) -> WalkSkip.Refused
        else -> null
    }

    /** Whether the folder whose real path is [real] is one in [excluded] or below one. */
    fun excludesFolder(real: Lazy<Path>): Boolean = excluded.isNotEmpty() && isExcluded(real.value)

    /** The names of the paths in [excluded], which a file that is no link must have to be one of them. */
    val excludedNames = excluded.mapTo(mutableSetOf()) { it.name }

    /** Whether the symlink [link] is in [excluded] by its own place, or below one, for a walk that follows no link. */
    fun excludesLinkAt(link: Path): Boolean =
        excluded.isNotEmpty() && isExcluded(lookups.ownPlace(link).getOrNull() ?: link)

    /** Whether [real] is a path in [excluded] or below one. */
    fun isExcluded(real: Path): Boolean = excluded.any { real.isAtOrBelow(it) }
}

/** One entry [walkTopDown] reached, at [path]: a folder root joined with [relative], or a file root itself. */
public sealed interface WalkEntry {
    public val path: Path

    /** Where [path] sits below the root, as the walk reached it; never empty, and a file root's own name. */
    public val relative: Path

    /**
     * A regular file, or a symlink to one, as the walk found it: [size] bytes, last written at [modifiedAt], and
     * [viaLink] when [path] is a symlink that leads to the file. All three are a snapshot of when the walk reached the
     * entry, and [size] and [modifiedAt] are those of the file a link leads to, as [info] reads them.
     */
    public data class File(
        override val path: Path,
        override val relative: Path,
        val size: Long,
        val modifiedAt: Instant,
        val viaLink: Boolean,
    ) : WalkEntry

    /**
     * A folder below the root, or a symlink to one, that the walk enters: yielded just before its own entries, and
     * what the walk's `enter` predicate is asked about before that. The root itself is never yielded.
     */
    public data class Folder(
        override val path: Path,
        override val relative: Path,
    ) : WalkEntry

    /**
     * A symlink, which only [Symlinks.AsLinks] yields, with its [target] as the link holds it: a relative one is read
     * from the link's folder. The walk neither follows it nor looks at what it leads to, which may be nothing.
     */
    public data class Link(
        override val path: Path,
        override val relative: Path,
        val target: Path,
    ) : WalkEntry

    /** An entry the walk passed over without reading or entering it, for the reason [why] gives. */
    public data class Skipped(
        override val path: Path,
        override val relative: Path,
        val why: WalkSkip,
    ) : WalkEntry
}

/**
 * What [walkTopDown] could not read at [path], an entry or a folder [relative] below the root, for what [error] says.
 * [path] is the root joined with [relative], as a [WalkEntry]'s is, so `dst / failure.relative` is where the copy of
 * it would go. [relative] is empty only for the root itself, when iterating the walk again finds it can no longer be
 * listed.
 */
public data class WalkFailure(
    val path: Path,
    val relative: Path,
    val error: FileError,
) : ResultError

/** Why [walkTopDown] passed over an entry. */
public sealed interface WalkSkip {
    /** A symlink whose target is missing, or a chain of links ending in one. */
    public data object DanglingLink : WalkSkip

    /** A symlink whose chain of links goes round, or runs longer than the system follows, so it leads nowhere. */
    public data object LinkLoop : WalkSkip

    /** Nothing was there any more when the walk looked the listed entry up, or listed the folder it had found. */
    public data object Gone : WalkSkip

    /** Neither a file to read nor a folder to enter: a FIFO, device, socket, or what [type] says. */
    public data class NotFileOrFolder(val type: FileType) : WalkSkip

    /**
     * A link to a folder the walk already walked under the first path that reached it, [firstRelative] below the
     * root, or null when that folder is the root itself. A folder inside the root is walked under its own path before
     * any link to it, so [firstRelative] is that path unless the walk reached the folder only through a link.
     */
    public data class AlreadyEntered(val firstRelative: Path?) : WalkSkip

    /**
     * A folder in the walk's `exclude` or below one, a file in it, or a symlink to a file in or below one; under
     * [Symlinks.AsLinks] and [Symlinks.NoLinks], a symlink `exclude` names by its own place.
     */
    public data object Excluded : WalkSkip

    /** A folder the walk's `enter` predicate refused. */
    public data object Refused : WalkSkip

    /**
     * A symlink, to a folder or a file, that [Symlinks.Inside] passes over, since it leads outside the root; or, in a
     * [mirrorTo] report, a link [Symlinks.AsLinks] yielded that was not made, since its target leads out by its names.
     */
    public data object LeadsOutside : WalkSkip

    /** A symlink, whatever it leads to, which [Symlinks.NoLinks] passes over. */
    public data object Link : WalkSkip

    /**
     * The entry changed while the walk looked at it, such as a folder swapped for a link, or a link that is no link any
     * more, so the walk passed over it rather than mix two answers. Walk again, or watch the folder, to see what it
     * became.
     */
    public data object Changed : WalkSkip
}

/** The walk under [root]: the first iteration starts from [rootEntries], and each later one lists [root] again. */
private fun walkFrom(
    root: Path,
    rootEntries: List<Path>,
    rules: WalkRules,
): Sequence<Result<WalkEntry, WalkFailure>> {
    var callTimeEntries: List<Path>? = rootEntries
    return Sequence {
        val listed = callTimeEntries?.let { Ok(it) } ?: rules.lookups.list(root)
        callTimeEntries = null
        entriesUnder(root, listed, rules).iterator()
    }
}

private fun entriesUnder(
    root: Path,
    rootListed: Result<List<Path>, FileError>,
    rules: WalkRules,
): Sequence<Result<WalkEntry, WalkFailure>> = sequence {
    val lookups = rules.lookups
    val pending = ArrayDeque<Judged>()
    // Each link to a folder, in the order met, taken once no real folder is left, so a folder is walked under its own
    // path before any link reaches it.
    val linkedFolders = ArrayDeque<WalkEntry.Folder>()
    val symlinks = rules.symlinks
    val realRoot = lookups.oneName(root)
    // Where each folder walked so far really is, and the relative path it was walked under, null for the root, so one
    // reached again through a link is recognised. Only a followed link can reach a folder twice.
    val entered = if (symlinks.followsLinks()) mutableMapOf<Path, Path?>(realRoot to null) else null
    val linksStayIn = realRoot.takeIf { symlinks == Symlinks.Inside }
    // The folder being walked, null for the root, which is never yielded, and where it resolved once listed.
    var current: Judged? = null
    var currentReal = realRoot
    var listed: Result<List<Path>, FileError>? = rootListed
    while (listed != null) {
        val folder = current?.entry
        when {
            // A folder below the root gone since its lookup is passed over; the root's own error stays the walk's.
            folder != null && listed is Result.Error && listed.error is FileError.NotFound ->
                yield(Ok(WalkEntry.Skipped(folder.path, folder.relative, WalkSkip.Gone)))
            listed is Result.Error -> {
                if (folder != null) yield(Ok(folder))
                yield(Err(WalkFailure(folder?.path ?: root, folder?.relative ?: Path(""), listed.error)))
            }
            listed is Result.Success -> {
                if (folder != null) yield(Ok(folder))
                val throughLink = current?.throughLink ?: false
                val above = currentReal.takeIf { entered == null }
                val subdirectories = mutableListOf<Judged>()
                for (child in listed.value) {
                    val childRelative = folder?.let { it.relative / child.name } ?: Path(child.name)
                    when (val step = child.walkStep(childRelative, entered, linksStayIn, rules)) {
                        is WalkStep.Folder -> subdirectories += step.judged(throughLink, above)
                        is WalkStep.LinkedFolder -> linkedFolders += step.entry
                        is WalkStep.Yielded -> yield(step.yielded(child, childRelative))
                    }
                }
                pending.addAll(0, subdirectories)
            }
        }
        current = null
        listed = null
        while (current == null) {
            val next = pending.removeFirstOrNull()
            if (next != null) {
                // Asked before the listing too, so a link put in its place is never opened, and after it, so one put
                // there while it was listed is never listed through.
                if (next.realIfAsJudged(lookups) != null) {
                    val listing = lookups.list(next.entry.path)
                    val real = next.realIfAsJudged(lookups)
                    if (real != null) {
                        current = next
                        currentReal = real
                        listed = listing
                        continue
                    }
                }
                next.real?.let { entered?.remove(it) }
                yield(Ok(WalkEntry.Skipped(next.entry.path, next.entry.relative, WalkSkip.Changed)))
                continue
            }
            val link = linkedFolders.removeFirstOrNull() ?: break
            when (val step = link.path.walkStep(link.relative, entered, linksStayIn, rules, deferLinks = false)) {
                // Checked by where it resolves, since a link, it or one above it, was followed to reach it.
                is WalkStep.Folder if step.viaLink -> pending.addFirst(step.judged(throughLink = true, above = null))
                is WalkStep.LinkedFolder -> error("a link to a folder is judged when the walk comes back to it")
                is WalkStep.Yielded if step.isOfALinkToAFolder() -> yield(step.yielded(link.path, link.relative))
                else -> yield(Ok(WalkEntry.Skipped(link.path, link.relative, WalkSkip.Changed)))
            }
        }
    }
}

/**
 * A folder the walk judged it enters, waiting for its listing. Where links are followed, [real] is where it resolved
 * then; a walk that follows no link instead has [above], where the folder it was listed in resolved, so a link put in
 * place of a folder above it is told. With [throughLink], its path passes through a followed link.
 */
private class Judged(
    val entry: WalkEntry.Folder,
    val real: Path?,
    val above: Path?,
    val throughLink: Boolean,
) {
    /**
     * Where the folder resolves, while it is still as judged: no link, unless reached through one, at [real] where
     * links are followed, and below the folder it was listed in, still at [above], where they are not. Null otherwise.
     */
    fun realIfAsJudged(lookups: WalkLookups): Path? {
        val path = entry.path
        val now = lookups.oneName(path)
        val asJudged = when {
            real != null -> now == real && (throughLink || !lookups.isLink(path))
            else -> !lookups.isLink(path) && lookups.oneName(path.folderOrDot()) == above
        }
        return now.takeIf { asJudged }
    }
}

/**
 * This step's folder, waiting for its listing, [throughLink] when the folder it was listed in is reached so, and
 * [above] where that folder resolved, for a walk that follows no link.
 */
private fun WalkStep.Folder.judged(
    throughLink: Boolean,
    above: Path?,
): Judged = Judged(entry, real, above, throughLink || viaLink)

/**
 * Whether this step is one a link to a folder can come to at its turn: passed over for what it leads to, or a failure
 * to read it. Anything else says it is no such link any more.
 */
private fun WalkStep.Yielded.isOfALinkToAFolder(): Boolean = when (this) {
    is WalkStep.Failed -> true
    is WalkStep.Skip -> when (why) {
        is WalkSkip.AlreadyEntered, WalkSkip.Excluded, WalkSkip.Refused, WalkSkip.LeadsOutside -> true
        else -> false
    }
    is WalkStep.File, is WalkStep.Link -> false
}

/** What the walk yields for this step at [path], [relative] below the root. */
private fun WalkStep.Yielded.yielded(
    path: Path,
    relative: Path,
): Result<WalkEntry, WalkFailure> = when (this) {
    is WalkStep.File -> Ok(path.fileEntry(relative, info, viaLink))
    is WalkStep.Link -> Ok(WalkEntry.Link(path, relative, target))
    is WalkStep.Failed -> Err(WalkFailure(path, relative, error))
    is WalkStep.Skip -> Ok(WalkEntry.Skipped(path, relative, why))
}

/** The file at this path, [relative] below the root, as [info] read it, [viaLink] when a symlink led to it. */
private fun Path.fileEntry(
    relative: Path,
    info: FileInfo,
    viaLink: Boolean,
): WalkEntry.File = WalkEntry.File(this, relative, info.size, info.modifiedAt, viaLink)

private fun FileError.isLoop(): Boolean = this is FileError.Inaccessible && cause == IoCause.Loop

/** What a walk does with one entry it listed. */
private sealed interface WalkStep {
    /** A step that enters no folder, yielded where the walk met it. */
    sealed interface Yielded : WalkStep

    data class File(val info: FileInfo, val viaLink: Boolean) : Yielded

    data class Link(val target: Path) : Yielded

    /** A folder to enter, [real] being where it resolved to when that was asked, [viaLink] when it is a link. */
    data class Folder(
        val entry: WalkEntry.Folder,
        val real: Path?,
        val viaLink: Boolean,
    ) : WalkStep

    /** A link to a folder, which the walk takes once no real folder is left. */
    data class LinkedFolder(val entry: WalkEntry.Folder) : WalkStep

    data class Failed(val error: FileError) : Yielded

    data class Skip(val why: WalkSkip) : Yielded
}

/**
 * What a walk does with this entry, found at [relative] below the root. [entered] maps where each folder walked so
 * far really is to the relative path it was walked under, null for the root, when links are followed, and is null
 * when they are not. With [linksStayIn], a symlink is followed only to a place in that folder or below it. With
 * [deferLinks], a link to a folder that [WalkRules.excludesFolder] keeps is a [WalkStep.LinkedFolder], judged when
 * the walk comes back to it.
 *
 * What the entry is and whether it is a symlink come from one lookup of the entry itself, and its real path is asked
 * once and reused. A folder that is no link, and a file that is no link but has the name of an excluded path, is
 * asked again whether it is one once resolved, since a link put in its place after the first look would have been
 * resolved instead: such an entry is [WalkSkip.Changed].
 */
private fun Path.walkStep(
    relative: Path,
    entered: MutableMap<Path, Path?>?,
    linksStayIn: Path?,
    rules: WalkRules,
    deferLinks: Boolean = true,
): WalkStep {
    val lookups = rules.lookups
    val (found, isLink, linkReal) = when (val looked = firstLook(rules)) {
        is Look.Found -> looked
        is Look.Done -> return looked.step
    }
    val real = lazy(LazyThreadSafetyMode.NONE) { linkReal ?: lookups.oneName(this) }
    if (linksStayIn != null && isLink && !real.value.isAtOrBelow(linksStayIn)) {
        return WalkStep.Skip(WalkSkip.LeadsOutside)
    }
    if (found.type == FileType.Regular) {
        val excluded = when {
            // Asked first, so a walk with nothing excluded never resolves a file.
            rules.excluded.isEmpty() -> false
            isLink -> rules.isExcluded(real.value)
            // By name first, so a file no excluded path could be is never resolved.
            name !in rules.excludedNames -> false
            else -> {
                val place = real.value
                // Where the mode follows links, a link put here since the look is excluded by that same real path.
                val settled = rules.symlinks.followsLinks() && place in rules.excluded
                if (!settled && lookups.isLink(this)) return WalkStep.Skip(WalkSkip.Changed)
                place in rules.excluded
            }
        }
        return if (excluded) WalkStep.Skip(WalkSkip.Excluded) else WalkStep.File(found, viaLink = isLink)
    }
    if (found.type != FileType.Directory) return WalkStep.Skip(WalkSkip.NotFileOrFolder(found.type))
    val folder = WalkEntry.Folder(this, relative)
    val realFolder = if (isLink || entered != null || rules.excluded.isNotEmpty()) real.value else null
    // Resolved after the look, so a folder swapped for a link since was resolved through it.
    if (realFolder != null && !isLink && lookups.isLink(this)) return WalkStep.Skip(WalkSkip.Changed)
    if (entered == null) {
        return rules.refusal(folder, real)?.let { WalkStep.Skip(it) } ?: WalkStep.Folder(folder, real = null, isLink)
    }
    checkNotNull(realFolder)
    // A folder no link that resolves outside is reached through a folder above it swapped for a link since.
    if (linksStayIn != null && !realFolder.isAtOrBelow(linksStayIn)) return WalkStep.Skip(WalkSkip.Changed)
    if (deferLinks && isLink) {
        return if (rules.excludesFolder(real)) WalkStep.Skip(WalkSkip.Excluded) else WalkStep.LinkedFolder(folder)
    }
    if (realFolder in entered) return WalkStep.Skip(WalkSkip.AlreadyEntered(firstRelative = entered[realFolder]))
    rules.refusal(folder, real)?.let { return WalkStep.Skip(it) }
    // Recorded only once entered, so a folder refused above is still walked under the next path that reaches it.
    entered[realFolder] = relative
    return WalkStep.Folder(folder, realFolder, isLink)
}

/**
 * A walk's look at this entry, from a lookup of the entry itself first, so what it is and whether it is a link come
 * from one answer: where links are followed, a symlink is then followed, and passed over or read as one otherwise.
 */
private fun Path.firstLook(rules: WalkRules): Look =
    if (rules.symlinks.followsLinks()) followingLook(rules.lookups) else ownLook(rules)

/**
 * The look at this entry of a walk that follows links: what it is itself, and when it is a link, where it resolves to
 * and what is there, read at that real path so both describe one target.
 */
private fun Path.followingLook(lookups: WalkLookups): Look {
    val own = when (val looked = lookups.ownInfo(this)) {
        is Result.Success -> looked.value
        is Result.Error -> return Look.Done(looked.error.failedLookupStep())
    }
    if (own.type != FileType.Symlink) return Look.Found(own, isLink = false)
    // A link's real path is never its own, so the same path back says it could not be resolved.
    val real = lookups.oneName(this).takeIf { it != this }
    val led = lookups.info(real ?: this)
    // A link both before and after the lookup of what it leads to, or else what was read may be a file put there.
    if (!lookups.isLink(this)) return Look.Done(WalkStep.Skip(WalkSkip.Changed))
    return when (led) {
        // A link that could not be resolved but leads somewhere now is resolved again where its real path is needed.
        is Result.Success -> Look.Found(led.value, isLink = true, real)
        is Result.Error -> Look.Done(led.error.failedFollowStep())
    }
}

/**
 * What a walk's first look at an entry found, whether it is a symlink, and the real path a link resolved to, or the
 * step that look already decides.
 */
private sealed interface Look {
    data class Found(
        val info: FileInfo,
        val isLink: Boolean,
        val real: Path? = null,
    ) : Look

    data class Done(val step: WalkStep) : Look
}

/**
 * The look at this entry of a walk that follows no link, from one lookup of the entry itself: a symlink is passed over
 * or read as one, and anything else is what that lookup found.
 */
private fun Path.ownLook(rules: WalkRules): Look {
    val lookups = rules.lookups
    val own = when (val looked = lookups.ownInfo(this)) {
        is Result.Success -> looked.value
        is Result.Error -> return Look.Done(looked.error.failedLookupStep())
    }
    if (own.type != FileType.Symlink) return Look.Found(own, isLink = false)
    if (rules.excludesLinkAt(this)) return Look.Done(WalkStep.Skip(WalkSkip.Excluded))
    if (rules.symlinks == Symlinks.NoLinks) return Look.Done(WalkStep.Skip(WalkSkip.Link))
    val target = try {
        lookups.linkTarget(this)
    } catch (e: Exception) {
        return lookAfterFailedRead(e, lookups)
    }
    return Look.Done(WalkStep.Link(target))
}

/** The look at this entry, a symlink whose read just failed with [failure], as [afterFailedLinkRead] looks again. */
private fun Path.lookAfterFailedRead(
    failure: Exception,
    lookups: WalkLookups,
): Look = when (val now = afterFailedLinkRead(failure, lookups::ownInfo)) {
    is Result.Success -> Look.Done(WalkStep.Skip(WalkSkip.Changed))
    is Result.Error -> Look.Done(now.error.failedLookupStep())
}

/** What a walk does with an entry whose lookup failed with this: one nothing is at any more is [WalkSkip.Gone]. */
private fun FileError.failedLookupStep(): WalkStep =
    if (this is FileError.NotFound) WalkStep.Skip(WalkSkip.Gone) else WalkStep.Failed(this)

/** What a walk does with an entry still a symlink after the lookup of what it leads to failed with this. */
private fun FileError.LookupError.failedFollowStep(): WalkStep = when {
    // Its parent was just listed, so only the entry's own chain of links can go round.
    isLoop() -> WalkStep.Skip(WalkSkip.LinkLoop)
    // Still there as a link, so it is the link's target that is missing.
    this is FileError.NotFound -> WalkStep.Skip(WalkSkip.DanglingLink)
    else -> WalkStep.Failed(this)
}
