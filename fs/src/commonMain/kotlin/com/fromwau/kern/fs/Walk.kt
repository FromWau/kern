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
 * A waiting link that changed before its turn, such as one pointed at a file or removed, is yielded as what it is
 * then, at that later point of the walk rather than among its folder's entries. The walk holds one entry per link
 * waiting.
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
 *   folder it refuses counts as not walked, so a later path to the same folder is asked again. What it throws ends
 *   the iteration.
 * @return the entries; [FileError.NotFound] or [FileError.Inaccessible] when the root cannot be looked up or listed,
 *   [FileError.NotRegularFile] when it is a FIFO, device or socket, [FileError.NotADirectory] when a folder root was
 *   swapped for a file before it could be listed, or what [realPath] answers for a folder in [exclude] that is there
 *   but cannot be resolved.
 */
public fun Path.walkTopDown(
    symlinks: Symlinks = Symlinks.Inside,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
): Result<Sequence<Result<WalkEntry, WalkFailure>>, FileError> {
    val found = when (val looked = info()) {
        is Result.Success -> looked.value
        is Result.Error -> return looked
    }
    val excluded = when (val resolved = exclude.realPaths(linksByPlace = !symlinks.followsLinks())) {
        is Result.Success -> resolved.value
        is Result.Error -> return resolved
    }
    val rules = WalkRules(symlinks, excluded, enter)
    return when (found.type) {
        FileType.Regular -> Ok(sequenceOf(Ok(fileEntry(Path(name), found))))
        FileType.Directory -> list().map { entries -> walkFrom(this, entries, rules) }
        else -> Err(FileError.NotRegularFile(this, found.type))
    }
}

/**
 * The real path of each of these that something is at, or the first error resolving one gave otherwise. With
 * [linksByPlace], a symlink stands for its own place, the real path of its folder joined with its name.
 */
private fun Set<Path>.realPaths(linksByPlace: Boolean): Result<Set<Path>, FileError> {
    val real = mutableSetOf<Path>()
    for (path in this) {
        val resolved = if (linksByPlace && path.isSymlink() == Ok(true)) path.ownPlace() else path.realPath()
        when (resolved) {
            is Result.Success -> real += resolved.value
            is Result.Error -> if (resolved.error !is FileError.NotFound) return resolved
        }
    }
    return Ok(real)
}

/** Where this symlink itself is: the real path of the folder it is in, joined with its name. */
private fun Path.ownPlace(): Result<Path, FileError> = folderOrDot().realPath().map { Path(it, name) }

/** Whether a walk in this mode follows a link, rather than passing it over or yielding it as one. */
private fun Symlinks.followsLinks(): Boolean = this == Symlinks.Inside || this == Symlinks.Anywhere

/** What a walk was asked to do with links, which folders it was told to leave out and which to [enter]. */
private class WalkRules(
    val symlinks: Symlinks,
    val excluded: Set<Path>,
    val enter: (WalkEntry.Folder) -> Boolean,
) {
    /** Why the walk does not enter [folder], whose real path is [real] when already known, or null when it does. */
    fun refusal(
        folder: WalkEntry.Folder,
        real: Path? = null,
    ): WalkSkip? = when {
        excludesFolder(real ?: folder.path.oneName()) -> WalkSkip.Excluded
        !enter(folder) -> WalkSkip.Refused
        else -> null
    }

    /** Whether the folder whose real path is [real] is one in [excluded] or below one. */
    fun excludesFolder(real: Path): Boolean = excluded.isNotEmpty() && isExcluded(real)

    private val excludedNames = excluded.mapTo(mutableSetOf()) { it.name }

    /**
     * Whether [file], a regular file the walk reached, is one in [excluded], or a symlink to one there or to one in a
     * folder in [excluded] or below one.
     */
    fun excludesFile(file: Path): Boolean {
        // Asked first, so a walk with nothing excluded never looks at what a file is.
        if (excluded.isEmpty()) return false
        if (isSymlink(file)) return isExcluded(file.oneName())
        // By name first, so a file no excluded path could be is never resolved.
        return file.name in excludedNames && file.oneName() in excluded
    }

    /** Whether [entry] is a symlink in [excluded] by its own place, or below one, for a walk that follows no link. */
    fun excludesLinkAt(entry: Path): Boolean {
        if (excluded.isEmpty() || symlinks.followsLinks() || !isSymlink(entry)) return false
        return isExcluded(entry.ownPlace().getOrNull() ?: entry)
    }

    private fun isExcluded(real: Path): Boolean = excluded.any { real.isAtOrBelow(it) }
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
}

/** The walk under [root]: the first iteration starts from [rootEntries], and each later one lists [root] again. */
private fun walkFrom(
    root: Path,
    rootEntries: List<Path>,
    rules: WalkRules,
): Sequence<Result<WalkEntry, WalkFailure>> {
    var callTimeEntries: List<Path>? = rootEntries
    return Sequence {
        val listed = callTimeEntries?.let { Ok(it) } ?: root.list()
        callTimeEntries = null
        entriesUnder(root, listed, rules).iterator()
    }
}

private fun entriesUnder(
    root: Path,
    rootListed: Result<List<Path>, FileError>,
    rules: WalkRules,
): Sequence<Result<WalkEntry, WalkFailure>> = sequence {
    val pending = ArrayDeque<WalkEntry.Folder>()
    // Each link to a folder, in the order met, taken once no real folder is left, so a folder is walked under its own
    // path before any link reaches it.
    val linkedFolders = ArrayDeque<WalkEntry.Folder>()
    val symlinks = rules.symlinks
    val realRoot = if (symlinks.followsLinks()) root.oneName() else null
    // Where each folder walked so far really is, and the relative path it was walked under, null for the root, so one
    // reached again through a link is recognised. Only a followed link can reach a folder twice.
    val entered = realRoot?.let { mutableMapOf<Path, Path?>(it to null) }
    val linksStayIn = realRoot.takeIf { symlinks == Symlinks.Inside }
    // The folder being walked, null for the root, which is never yielded.
    var current: WalkEntry.Folder? = null
    var listed: Result<List<Path>, FileError>? = rootListed
    while (listed != null) {
        val folder = current
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
                val subdirectories = mutableListOf<WalkEntry.Folder>()
                for (child in listed.value) {
                    val childRelative = folder?.let { it.relative / child.name } ?: Path(child.name)
                    when (val step = child.walkStep(childRelative, entered, linksStayIn, rules)) {
                        is WalkStep.Folder -> subdirectories += step.entry
                        is WalkStep.LinkedFolder -> linkedFolders += step.entry
                        is WalkStep.Yielded -> yield(step.yielded(child, childRelative))
                    }
                }
                pending.addAll(0, subdirectories)
            }
        }
        current = pending.removeFirstOrNull()
        while (current == null) {
            val link = linkedFolders.removeFirstOrNull() ?: break
            when (val step = link.path.walkStep(link.relative, entered, linksStayIn, rules, deferLinks = false)) {
                is WalkStep.Folder -> current = step.entry
                is WalkStep.LinkedFolder -> error("a link to a folder is judged when the walk comes back to it")
                is WalkStep.Yielded -> yield(step.yielded(link.path, link.relative))
            }
        }
        listed = current?.path?.list() ?: break
    }
}

/** What the walk yields for this step at [path], [relative] below the root. */
private fun WalkStep.Yielded.yielded(
    path: Path,
    relative: Path,
): Result<WalkEntry, WalkFailure> = when (this) {
    is WalkStep.File -> Ok(path.fileEntry(relative, info))
    is WalkStep.Link -> Ok(WalkEntry.Link(path, relative, target))
    is WalkStep.Failed -> Err(WalkFailure(path, relative, error))
    is WalkStep.Skip -> Ok(WalkEntry.Skipped(path, relative, why))
}

/** The file at this path, [relative] below the root, as [info] read it. */
private fun Path.fileEntry(
    relative: Path,
    info: FileInfo,
): WalkEntry.File = WalkEntry.File(this, relative, info.size, info.modifiedAt, viaLink = isSymlink(this))

private fun FileError.isLoop(): Boolean = this is FileError.Inaccessible && cause == IoCause.Loop

/** What a walk does with one entry it listed. */
private sealed interface WalkStep {
    /** A step that enters no folder, yielded where the walk met it. */
    sealed interface Yielded : WalkStep

    data class File(val info: FileInfo) : Yielded

    data class Link(val target: Path) : Yielded

    data class Folder(val entry: WalkEntry.Folder) : WalkStep

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
 */
private fun Path.walkStep(
    relative: Path,
    entered: MutableMap<Path, Path?>?,
    linksStayIn: Path?,
    rules: WalkRules,
    deferLinks: Boolean = true,
): WalkStep {
    if (rules.excludesLinkAt(this)) return WalkStep.Skip(WalkSkip.Excluded)
    if (rules.symlinks == Symlinks.NoLinks && isSymlink(this)) return WalkStep.Skip(WalkSkip.Link)
    if (rules.symlinks == Symlinks.AsLinks && isSymlink(this)) {
        when (val read = readSymlink()) {
            is Result.Success -> return WalkStep.Link(read.value)
            is Result.Error -> when (read.error) {
                is FileError.NotFound -> return WalkStep.Skip(WalkSkip.Gone)
                // No longer a link since it was listed, so it is looked at below as what it is now.
                is FileError.NotASymlink -> Unit
                else -> return WalkStep.Failed(read.error)
            }
        }
    }
    val found = when (val looked = info()) {
        is Result.Success -> looked.value
        is Result.Error -> return when {
            // Its parent was just listed, so only the entry's own chain of links can go round.
            looked.error.isLoop() && isSymlink(this) -> WalkStep.Skip(WalkSkip.LinkLoop)
            looked.error !is FileError.NotFound -> WalkStep.Failed(looked.error)
            // Still there as a link, so it is the link's target that is missing.
            isSymlink(this) -> WalkStep.Skip(WalkSkip.DanglingLink)
            else -> WalkStep.Skip(WalkSkip.Gone)
        }
    }
    if (linksStayIn != null && isSymlink(this) && !oneName().isAtOrBelow(linksStayIn)) {
        return WalkStep.Skip(WalkSkip.LeadsOutside)
    }
    if (found.type == FileType.Regular) {
        return if (rules.excludesFile(this)) WalkStep.Skip(WalkSkip.Excluded) else WalkStep.File(found)
    }
    if (found.type != FileType.Directory) return WalkStep.Skip(WalkSkip.NotFileOrFolder(found.type))
    val folder = WalkEntry.Folder(this, relative)
    if (entered == null) return rules.refusal(folder)?.let { WalkStep.Skip(it) } ?: WalkStep.Folder(folder)
    val real = oneName()
    if (deferLinks && isSymlink(this)) {
        return if (rules.excludesFolder(real)) WalkStep.Skip(WalkSkip.Excluded) else WalkStep.LinkedFolder(folder)
    }
    if (real in entered) return WalkStep.Skip(WalkSkip.AlreadyEntered(firstRelative = entered[real]))
    rules.refusal(folder, real)?.let { return WalkStep.Skip(it) }
    // Recorded only once entered, so a folder refused above is still walked under the next path that reaches it.
    entered[real] = relative
    return WalkStep.Folder(folder)
}
