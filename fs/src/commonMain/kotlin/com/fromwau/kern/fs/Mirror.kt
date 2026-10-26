package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import kotlinx.io.files.Path

/**
 * Makes [dst] hold what this folder holds: every folder and file the walk of this folder yields is made or copied into
 * [dst], and whatever else [dst] holds is removed, unless an entry the walk passed over or could not read holds it, or
 * [keep] keeps it.
 *
 * ```kotlin
 * val published = dirs.data.writer("site").getOrElse { return warn(it) }
 * val report = site.mirrorTo(published, enter = { it.path.name != ".git" }).getOrElse { return warn(it) }
 * if (!report.isClean) report.unclean.forEach { warn("not mirrored: $it") }
 * ```
 *
 * Everything done in [dst] goes through the [Writer], so every folder made, file copied and entry removed stays below
 * its root: a symlink in [dst] is never followed, and one where this folder has a folder or a file is removed first.
 * A folder goes to its own relative path, and a file into its folder's, under the name [outputOf] gives it. Two
 * entries with one output: the first in walk order takes it, and the other is a [FileError.OutputClash]
 * among the failures, never copied. Nothing below a folder that loses its output is copied, made or reported. A
 * folder whose output still holds what stood there, such as a link a declined removal kept, is a failure, and so is
 * each entry below it, with the same error; nothing is written through what stands there. The walk of this folder
 * leaves [dst] out, so a [dst] inside it is never mirrored into itself and is not reported. Under [Symlinks.Anywhere],
 * and under [Symlinks.Inside] with [dst] inside this folder, a link here leading to [dst] or into it is in
 * [MirrorReport.held] as [WalkSkip.Excluded]: what [dst] has at its place stays, and the run stays clean. Under
 * [Symlinks.Inside] with [dst] outside, such a link leads outside, [WalkSkip.LeadsOutside], as any other does. A folder
 * whose walk yields no file or folder is planned like any other, with [RemovalPlan.sourceEmpty] set.
 *
 * Nothing in [dst] changes until a walk of this folder and one of [dst] have planned the whole run, which holds paths
 * and types, never what a file holds. What stands in an entry's way is removed just before the entry is placed: a file
 * or link where this folder now has a folder, a folder with all it holds where it now has a file, and a link, FIFO,
 * socket or device at a file's output. What [dst] holds that no entry yielded or holds is removed once the copying is
 * done, each folder after what it holds. Such an entry named like a write's leftover or lying in one, a temp or a
 * backup alike, goes before anything is copied instead, since a backup beside a file stops its copy. A folder of [dst]
 * holding an entry that could not be read stays, and so does each folder above it. Entries only [dst] has, such as its
 * own `.git` or a `CNAME`, go too, unless [keep] keeps them.
 *
 * An entry the walk passed over, such as a folder [enter] refused or a dangling link, and one it could not read, hold
 * what [dst] has at both its `relative` path and the output it would have as a file, whole folders included, since
 * neither a skip nor a failure always says whether the entry was a file. So the copy of a file that is now a dangling
 * link stays for as long as the link does, and the copy of one gone while the walk ran goes on the next run that no
 * longer lists it. The folders an output or a held path lies in stay too.
 *
 * Some entries the walk passes over hold nothing: a link that leads outside this folder, [WalkSkip.LeadsOutside], any
 * link when none is followed, [WalkSkip.Link], a FIFO, socket or device, [WalkSkip.NotFileOrFolder], and a folder
 * already walked under another path, [WalkSkip.AlreadyEntered]. What [dst] has at the place of one is planned for
 * removal as [RemovalReason.AtPassedOver], which the default [approveRemovals] leaves out, since it may be an earlier
 * copy you still want: a source folder now a link leading out, say. Each is listed in [MirrorReport.passedOver]. A
 * removal declined at its place leaves the report not clean, as any declined removal does; one carried out does not.
 *
 * @param symlinks which links in this folder the walk follows, as [walkTopDown] takes it. By default,
 *   [Symlinks.AsLinks], none is: each link is made again under its own name with [Writer.switchSymlink], its target
 *   as written. A link already there with that target is left alone, and a file or folder in its way is removed as
 *   for any entry; with that removal declined, the link is [FileError.AlreadyExists]. A link whose target leads out of
 *   this folder by its names at any step, an absolute one included, is not made: it is in [MirrorReport.passedOver]
 *   as [WalkSkip.LeadsOutside], and the run is not clean. Under a mode that follows links, a link followed to a file
 *   is copied as the file it leads to, and one to a folder is copied as that folder.
 * @param exclude folders and files the walk leaves out, as [walkTopDown] takes them, beside [dst] itself.
 * @param enter asked about each folder before the walk enters it, as [walkTopDown] asks; a folder it refuses holds
 *   what [dst] has at its place.
 * @param keep whether an entry of [dst] that nothing in this folder yields or holds stays, from its path relative to
 *   [dst]; asked while the run is planned, before anything is copied or removed, and never about what stands in an
 *   entry's way. One it answers true for is never removed, nor are the folders it lies in. A kept folder is not looked
 *   into, so all it holds stays, and none of them is listed in the [MirrorReport]. Nothing is kept by default. What it
 *   throws ends the mirror.
 * @param approveRemovals asked once, before anything is copied or removed, with the [RemovalPlan]: every path the run
 *   would remove and why, in the order it would, how many of them are files and folders, and how many entries [dst]
 *   holds. It is not asked when nothing would be removed. It answers the removals to carry out, matched by path; one
 *   not in the plan is ignored. The rest stay, and the [MirrorReport] lists them as `removalsDeclined`. A folder that
 *   would still hold one of them stays too, and so does all that stands in an entry's way when any of it stays. Such
 *   an entry is then a failure, [FileError.NotADirectory] for a folder and [FileError.NotRegularFile] for a file,
 *   naming what is at its output; the rest are copied as usual. By default it answers [RemovalPlan.defaultApproved]:
 *   none for a source that yielded nothing, [RemovalPlan.sourceEmpty], and otherwise every removal but those at the
 *   place of an entry the walk passed over, [RemovalReason.AtPassedOver]. What it throws ends the mirror.
 * @param outputOf the name a file goes to in its folder's output, from its path relative to this folder; by default
 *   its own name. Only the last name of what it answers counts, so a site builder answering `thing.html/x.html` for
 *   `thing.md/x.md` writes `thing.md/x.html`: a folder always keeps its own name, and the tree stays one tree. It is
 *   never asked about a folder the walk yields, and is asked about an entry the walk passed over or could not read,
 *   which may be a file. A name [Writer] refuses is that entry's failure.
 * @param isCurrent whether the regular file already at a file's output is current, so it is left alone and listed as
 *   [MirrorReport.unchanged]. It is asked only when a regular file is there, never about a symlink or anything else.
 *   By default a file is current when its size and modification time match the source's, as a copy with
 *   `keepAttributes` leaves them.
 * @param copy writes a file to the output `name` through `out`, which is [dst]. By default it is [Writer.copyFrom]
 *   with `keepAttributes`, so a hard link at the name is cut, never written through. A symlink there is removed
 *   before it is asked, as what stands in the way. What it throws ends the mirror.
 * @return what was done, as a [MirrorReport]; [FileError.NotADirectory] when this path is not a folder, since a walk
 *   of a file yields only that file and the removals would empty [dst]; [FileError.MirrorOverlap] when this folder is
 *   [dst]'s root or lies below it, by the [realPath] of this folder; [FileError.FolderReplaced] when [dst]'s root is
 *   no longer the folder it was made for; or what looking this folder up, resolving it or starting the walk answers.
 *   Nothing in [dst] is changed on any of these.
 */
public fun Path.mirrorTo(
    dst: Writer,
    symlinks: Symlinks = Symlinks.AsLinks,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
    keep: (relative: Path) -> Boolean = { false },
    approveRemovals: (plan: RemovalPlan) -> List<Removal> = { it.defaultApproved() },
    outputOf: (relative: Path) -> String = { it.writerName() },
    isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean = { source, existing ->
        existing.size == source.size && existing.modifiedAt == source.modifiedAt
    },
    copy: (source: WalkEntry.File, out: Writer, name: String) -> EmptyResult<FileError> = { source, out, name ->
        out.copyFrom(source.path, name, keepAttributes = true).map { }
    },
): Result<MirrorReport, FileError> {
    val rootType = when (val looked = fileType()) {
        is Result.Success -> looked.value
        is Result.Error -> return looked
    }
    if (rootType != FileType.Directory) return Err(FileError.NotADirectory(this, rootType))
    val realRoot = when (val resolved = realPath()) {
        is Result.Success -> resolved.value
        is Result.Error -> return resolved
    }
    if (realRoot.relativeTo(dst.root) != null) return Err(FileError.MirrorOverlap(this, dst.root))
    dst.checkRoot().onError { return Err(it) }
    val walk = when (val started = walkTopDown(symlinks, exclude + dst.root, enter)) {
        is Result.Success -> started.value
        is Result.Error -> return started
    }
    val run = MirrorRun(this, dst, keep, outputOf)
    for (step in walk) {
        when (step) {
            is Result.Success -> run.plan(step.value)
            is Result.Error -> run.fail(step.error)
        }
    }
    val planned = run.planRemovals()
    val approved = if (planned.removals.isEmpty()) emptyList() else approveRemovals(planned)
    run.carryOut(planned.removals, approved.mapTo(mutableSetOf()) { it.path }, isCurrent, copy)
    return Ok(run.report())
}

/**
 * What a [mirrorTo] would remove, as its `approveRemovals` is asked about it.
 *
 * @property removals every path the run would remove, relative to the destination, with why, in the order it would:
 *   what no entry yields or holds that is named like a write's leftover or lying in one, then what stands in each
 *   entry's way, in walk order, then the rest of what no entry yields or holds, each folder after what it holds.
 * @property files how many of [removals] are files, links or anything else that is no folder.
 * @property folders how many of [removals] are folders.
 * @property dstEntries how many entries the destination holds below its root, as the run's walk of it found them: a
 *   folder that is kept or held, which the walk does not look into, counts as one. Set against [removals], it tells a
 *   run that would empty most of the destination from one that tidies a few files.
 * @property sourceEmpty whether the walk of the source yielded no file or folder at all. Such a source is more likely
 *   wrong than empty, and the default `approveRemovals` answers none of its removals.
 */
public data class RemovalPlan(
    val removals: List<Removal>,
    val files: Int,
    val folders: Int,
    val dstEntries: Int,
    val sourceEmpty: Boolean,
) {
    /**
     * The removals the default `approveRemovals` of [mirrorTo] answers: none when [sourceEmpty], and otherwise every
     * removal but those [RemovalReason.AtPassedOver]. Build an approver of your own on it to keep that rule.
     */
    public fun defaultApproved(): List<Removal> =
        if (sourceEmpty) emptyList() else removals.filter { it.why !is RemovalReason.AtPassedOver }
}

/** One path a [mirrorTo] would remove, relative to the destination, and [why]. */
public data class Removal(
    val path: Path,
    val why: RemovalReason,
)

/** Why a [mirrorTo] would remove a path of the destination. */
public sealed interface RemovalReason {
    /** Nothing in the source yields or holds it any more. */
    public data object Stale : RemovalReason

    /** It stands where an entry of the source goes, as another type, or lies in what does. */
    public data object InTheWay : RemovalReason

    /**
     * It is at the place of an entry the walk passed over as [skip], or lies in it: a link leading out, a FIFO or
     * another entry that is neither file nor folder, or a folder already walked under another path. Such an entry
     * holds nothing, yet what the destination has there may be an earlier copy you still want.
     *
     * @property place that entry's path relative to the source. Every removal in the old copy of a folder `photos` now
     *   a link leading out has `photos` here, and the removal at the output `outputOf` gives that entry names it too:
     *   `b.html` has `b.md` here when `b.md` is a link passed over.
     */
    public data class AtPassedOver(
        val skip: WalkSkip,
        val place: Path,
    ) : RemovalReason
}

/**
 * What [mirrorTo] did. [copied] lists the files copied and [unchanged] those [mirrorTo]'s `isCurrent` found current and
 * left alone, each as a [Mirrored] naming it in the source and its output in the destination. [removed] lists the
 * entries removed, relative to the destination, in the order removed. Under [Symlinks.AsLinks], a link made or
 * switched to another target is in [copied], and one already right in [unchanged]. A link at a file's output that the
 * file replaced is in [removed], and the file in [copied].
 *
 * | lists | each one names |
 * |---|---|
 * | [copied], [unchanged] | both sides: a [Mirrored] with the source's relative path and the destination's output |
 * | [removed] | the destination: a path relative to its root |
 * | [removalsDeclined] | the destination: a [Removal], its path relative to the root and why it was planned |
 * | [held], [passedOver], [failures] | the source: an entry of its walk, with its relative path |
 * | [notRemoved] | the destination: a [FileError] naming the entry by its path there, not relative |
 *
 * @property held each entry the walk passed over that holds what the destination has at its place, as [mirrorTo]
 *   describes; those in [passedOver] are not among them.
 * @property passedOver each entry the walk passed over as [WalkSkip.LeadsOutside], [WalkSkip.Link],
 *   [WalkSkip.NotFileOrFolder] or [WalkSkip.AlreadyEntered], and each link [Symlinks.AsLinks] yielded that was not
 *   made since it leads out, as [WalkSkip.LeadsOutside], in walk order. Such an entry holds nothing, so what the
 *   destination had at its place is planned for removal as [RemovalReason.AtPassedOver].
 * @property failures each entry the walk could not read, which holds what the destination has at its place, each
 *   file or folder that could not be copied, made, or cleared of what stood at its output, with the error met, each
 *   entry below a folder that could not be cleared, with that folder's error, and each entry whose output an earlier
 *   one took, as [FileError.OutputClash].
 * @property notRemoved what kept an entry the source no longer has from being removed, once per path: the error
 *   removing it, or the error reading a folder of the destination, which then stays with all it holds.
 * @property removalsDeclined each planned removal that stayed, with the reason it was planned: each `approveRemovals`
 *   did not answer, each folder kept since it still holds one of those, and the rest of an entry's way once part of
 *   it stays; in the order it would have been.
 * @property sourceEmpty whether the walk of the source yielded no file or folder at all, as [RemovalPlan.sourceEmpty]
 *   says.
 * @property unclean each reason the destination may not hold what the source holds, as an [Unclean] naming the entry
 *   of this report it is, in this order: each of [failures], of [notRemoved] and of [removalsDeclined], each entry of
 *   [held] but those you asked the walk to pass over with `exclude` or `enter` and links to the destination, which
 *   [mirrorTo] excludes itself, and each link [Symlinks.AsLinks] yielded that was not made since it leads out. Filter
 *   it to accept some, such as a held [WalkSkip.DanglingLink].
 * @property isClean whether [unclean] is empty, so the destination now holds what the source holds.
 *
 * Printed, it is one line: each list's size but [unclean]'s, the first five entries of each that holds any, then
 * [isClean], as `MirrorReport(copied=2 [a.md -> a.html, b.txt], unchanged=0, ..., passedOver=1 [photos: LeadsOutside],
 * ..., clean=false)`. An entry shows its relative path and a failure its kind beside it. A [notRemoved] error shows the
 * absolute path it names in the destination and its kind. A longer list ends in `...N more`. Each name over 64
 * characters is cut there and followed by `... (N bytes)`, N its length in UTF-8.
 */
@ConsistentCopyVisibility
public data class MirrorReport internal constructor(
    val copied: List<Mirrored>,
    val unchanged: List<Mirrored>,
    val removed: List<Path>,
    val held: List<WalkEntry.Skipped>,
    val passedOver: List<WalkEntry.Skipped>,
    val failures: List<WalkFailure>,
    val notRemoved: List<FileError>,
    val removalsDeclined: List<Removal>,
    val sourceEmpty: Boolean,
    val unclean: List<Unclean>,
) {
    public val isClean: Boolean get() = unclean.isEmpty()

    /** One reason a [MirrorReport] is not clean, naming what in the report it is. */
    public sealed interface Unclean {
        /** An entry of [failures]. */
        public data class Failed(val failure: WalkFailure) : Unclean

        /** An entry of [notRemoved]. */
        public data class NotRemoved(val error: FileError) : Unclean

        /** An entry of [removalsDeclined]. */
        public data class Kept(val removal: Removal) : Unclean

        /**
         * An entry of [held] that neither `exclude` nor `enter` asked the walk to pass over, and that is no link to the
         * destination, which [mirrorTo] excludes itself.
         */
        public data class Held(val entry: WalkEntry.Skipped) : Unclean

        /** An entry of [passedOver]: a link [Symlinks.AsLinks] yielded that was not made, since it leads out. */
        public data class LinkNotMade(val entry: WalkEntry.Skipped) : Unclean
    }

    override fun toString(): String = listOf(
        listed("copied", copied) { it.printed() },
        listed("unchanged", unchanged) { it.printed() },
        listed("removed", removed) { it.namesCutToPrint() },
        listed("held", held) { "${it.relative.namesCutToPrint()}: ${it.why}" },
        listed("passedOver", passedOver) { "${it.relative.namesCutToPrint()}: ${it.why}" },
        listed("failures", failures) { "${it.relative.namesCutToPrint()}: ${it.error.kind()}" },
        listed("notRemoved", notRemoved) { "${it.path.namesCutToPrint()}: ${it.kind()}" },
        listed("removalsDeclined", removalsDeclined) { it.path.namesCutToPrint() },
        "clean=$isClean",
    ).joinToString(prefix = "MirrorReport(", postfix = ")")
}

/** [name], the size of [entries] and, when there are any, the first [PRINTED_ENTRIES] of them as [show] prints each. */
private fun <T> listed(
    name: String,
    entries: List<T>,
    show: (T) -> String,
): String {
    if (entries.isEmpty()) return "$name=0"
    val shown = entries.take(PRINTED_ENTRIES).map(show)
    val more = if (entries.size > PRINTED_ENTRIES) listOf("...${entries.size - PRINTED_ENTRIES} more") else emptyList()
    return "$name=${entries.size} ${(shown + more).joinToString(prefix = "[", postfix = "]")}"
}

private const val PRINTED_ENTRIES = 5

private fun Mirrored.printed(): String {
    val from = relative.namesCutToPrint()
    return if (relative == output) from else "$from -> ${output.namesCutToPrint()}"
}

private fun FileError.kind(): String = this::class.simpleName ?: "FileError"

/**
 * A file or link [mirrorTo] placed: [relative], its path relative to the source, went to [output], its path relative
 * to the destination. The two differ where `outputOf` renamed it: `a.md` and `a.html` for a site builder.
 */
public data class Mirrored(
    val relative: Path,
    val output: Path,
)

/** One [mirrorTo] of [source] into [dst]: the plan it makes from the walks of [source] and [dst], then carries out. */
private class MirrorRun(
    private val source: Path,
    private val dst: Writer,
    private val keep: (relative: Path) -> Boolean,
    private val outputOf: (relative: Path) -> String,
) {
    /** Whether the walk yielded any file or folder. */
    private var yieldedAny = false

    /** Each link [Symlinks.AsLinks] yielded that was passed over, since it leads out of [source]. */
    private val linksNotMade = mutableListOf<WalkEntry.Skipped>()

    /** The relative paths in [dst] that stay: each yielded output, and the folders above it or above a held path. */
    private val kept = mutableSetOf<Path>()

    /** The relative paths in [dst] that a skipped or unread entry holds. */
    private val holds = mutableSetOf<Path>()

    /** The relative paths in [dst] at the place of an entry in [passedOver], with the removal reason there. */
    private val passedOverPlaces = mutableMapOf<Path, RemovalReason.AtPassedOver>()

    /** Each output taken so far, with the relative path of the entry that took it first. */
    private val takenBy = mutableMapOf<Path, Path>()

    /** The folders of the source that lost their output to an earlier entry, whose entries are passed by. */
    private val lostFolders = mutableSetOf<Path>()
    private val placements = mutableListOf<Placement>()

    /** Each output where what stands in its entry's way is removed first, with that entry's placement. */
    private val cleared = mutableMapOf<Path, Placement>()

    /** The stale entries named like a leftover, or lying in one, removed before any entry is placed. */
    private val staleLeftovers = mutableListOf<Path>()
    private val staleRemovals = mutableListOf<Path>()
    private val copied = mutableListOf<Mirrored>()
    private val unchanged = mutableListOf<Mirrored>()
    private val removed = mutableListOf<Path>()
    private val held = mutableListOf<WalkEntry.Skipped>()
    private val passedOver = mutableListOf<WalkEntry.Skipped>()
    private val failures = mutableListOf<WalkFailure>()
    private val notRemoved = mutableListOf<FileError>()
    private val removalsDeclined = mutableListOf<Removal>()

    fun fail(failure: WalkFailure) {
        if (failure.relative.liesIn(lostFolders)) return
        failures += failure
        hold(failure.relative)
    }

    fun plan(entry: WalkEntry) {
        if (entry.relative.liesIn(lostFolders)) return
        if (entry is WalkEntry.Link && entry.leadsOutOf(source)) {
            val notMade = WalkEntry.Skipped(entry.path, entry.relative, WalkSkip.LeadsOutside)
            passedOver += notMade
            linksNotMade += notMade
            passedOverAt(entry.relative, WalkSkip.LeadsOutside)
            return
        }
        if (entry is WalkEntry.Skipped) {
            if (entry.holdsNothing()) {
                passedOver += entry
                passedOverAt(entry.relative, entry.why)
            } else if (entry.holds()) {
                held += entry
                hold(entry.relative)
            }
            return
        }
        yieldedAny = true
        val name = if (entry is WalkEntry.File) outputName(entry.relative) else entry.relative.writerName()
        val output = Path(name)
        keepWithParents(output)
        val first = takenBy.getOrPut(output) { entry.relative }
        if (first != entry.relative) {
            failures += WalkFailure(entry.path, entry.relative, FileError.OutputClash(dst.root / output, first))
            if (entry is WalkEntry.Folder) lostFolders += entry.relative
            return
        }
        // What a planned removal above takes away is not looked at: it may be a link leading out of dst.
        val found = if (output.liesIn(cleared.keys)) {
            null
        } else {
            when (val looked = dst.typeAt(name)) {
                is Result.Success -> looked.value
                is Result.Error -> {
                    failed(entry, looked.error)
                    if (entry is WalkEntry.Folder) lostFolders += entry.relative
                    return
                }
            }
        }
        val target = dst.root / output
        val obstacle = when (entry) {
            is WalkEntry.Folder -> found?.takeIf { it != FileType.Directory }?.let {
                FileError.NotADirectory(target, it)
            }
            is WalkEntry.Link -> found?.takeIf { it != FileType.Symlink }?.let {
                FileError.AlreadyExists(target, it)
            }
            // A link goes as what stands in the way, so the copy finds nothing there whatever writes it.
            else -> found?.takeIf { it != FileType.Regular }?.let {
                FileError.NotRegularFile(target, it)
            }
        }
        val placement = Placement(entry, name, output, found, obstacle)
        if (obstacle != null) cleared[output] = placement
        placements += placement
    }

    /**
     * Every removal the plan makes, relative to [dst], in the order it would happen: the stale leftovers in reverse
     * walk order, then what stands in each entry's way, in walk order and each folder after what it holds, then in
     * reverse walk order what no entry keeps or holds, nor [keep] keeps.
     */
    fun planRemovals(): RemovalPlan {
        val enter = { folder: WalkEntry.Folder ->
            folder.relative.clearedBy() != null || (folder.relative !in holds && !folder.isKeptByCaller())
        }
        val walk = when (val started = dst.root.walkTopDown(Symlinks.NoLinks, enter = enter)) {
            is Result.Success -> started.value
            is Result.Error -> {
                notRemoved += started.error
                cleared.values.forEach { it.unclearable = started.error }
                return RemovalPlan(emptyList(), files = 0, folders = 0, dstEntries = 0, sourceEmpty = !yieldedAny)
            }
        }
        val inTheWay = cleared.values.associateWith { mutableListOf<Path>() }
        val stale = mutableListOf<Path>()
        // What stays though no entry keeps it: what could not be read, and what keep kept.
        val staying = mutableSetOf<Path>()
        val folders = mutableSetOf<Path>()
        var dstEntries = 0
        for (step in walk) {
            when (step) {
                is Result.Success -> {
                    val relative = step.value.relative
                    dstEntries++
                    if (step.value is WalkEntry.Folder) folders += relative
                    val placement = relative.clearedBy()
                    when {
                        placement == null -> when {
                            relative in kept || relative in holds -> Unit
                            keep(relative) -> staying += relative
                            else -> stale += relative
                        }
                        relative != placement.output -> inTheWay.getValue(placement) += relative
                    }
                }
                is Result.Error -> {
                    val placement = step.error.relative.clearedBy()
                    if (placement != null) {
                        placement.unclearable = placement.unclearable ?: step.error.error
                    } else {
                        notRemoved += step.error.error
                        staying += step.error.relative
                    }
                }
            }
        }
        for ((placement, inside) in inTheWay) {
            if (placement.unclearable == null) placement.clearing = inside.asReversed() + placement.output
        }
        // Each folder came before what it holds, so in reverse every folder is empty by the time it is removed. One
        // above an entry that stays cannot be emptied.
        val blocked = staying.flatMap { generateSequence(it) { level -> level.parent } }.toSet()
        // A stale backup beside a file makes its copy fail as [FileError.PendingBackup], so it goes first.
        val (leftovers, rest) = stale
            .asReversed()
            .filter { it !in blocked }
            .partition { it.isInLeftover() }
        staleLeftovers += leftovers
        staleRemovals += rest
        val inTheWayRemovals = placements.flatMap { it.clearing }.map { Removal(it, RemovalReason.InTheWay) }
        val removals = staleLeftovers.map(::staleRemoval) + inTheWayRemovals + staleRemovals.map(::staleRemoval)
        val removedFolders = removals.count { it.path in folders }
        val files = removals.size - removedFolders
        return RemovalPlan(removals, files, removedFolders, dstEntries, sourceEmpty = !yieldedAny)
    }

    /** Places every planned entry, carrying out only the removals of [planned] whose path is in [approved]. */
    fun carryOut(
        planned: List<Removal>,
        approved: Set<Path>,
        isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean,
        copy: (source: WalkEntry.File, out: Writer, name: String) -> EmptyResult<FileError>,
    ) {
        val declined = declined(planned, approved)
        removalsDeclined += planned.filter { it.path in declined }
        removeStale(staleLeftovers.filter { it !in declined })
        // What still stands at a folder's output, such as a link, must never be written through.
        val blocked = mutableMapOf<Path, FileError>()
        for (placement in placements) {
            val refusal = placement.output.blockedBy(blocked)
                ?: placement.unclearable
                ?: placement.obstacle?.takeIf { placement.output in declined }
                ?: placement.clearing.firstNotNullOfOrNull(::clear)
            if (refusal == null) {
                place(placement, isCurrent, copy)
                continue
            }
            failed(placement.entry, refusal)
            if (placement.entry is WalkEntry.Folder) blocked[placement.output] = refusal
        }
        removeStale(staleRemovals.filter { it !in declined })
    }

    /**
     * The paths of [planned] that stay: each not in [approved], each folder that would still hold one of them, and
     * everything in an entry's way when any of that stays, since the entry cannot be placed then.
     */
    private fun declined(
        planned: List<Removal>,
        approved: Set<Path>,
    ): Set<Path> {
        val declined = mutableSetOf<Path>()
        val holding = mutableSetOf<Path>()
        // Each folder is planned after what it holds, so what it still holds is known by its turn.
        for (removal in planned) {
            if (removal.path in approved && removal.path !in holding) continue
            declined += removal.path
            generateSequence(removal.path.parent) { it.parent }.forEach { holding += it }
        }
        for (placement in placements) {
            if (placement.clearing.any { it in declined }) declined += placement.clearing
        }
        return declined
    }

    private fun removeStale(paths: List<Path>) {
        for (relative in paths) {
            when (val deleted = dst.delete(relative.writerName())) {
                is Result.Success -> removed += relative
                is Result.Error -> notRemoved += deleted.error
            }
        }
    }

    fun report(): MirrorReport {
        val unclean = failures.map(MirrorReport.Unclean::Failed) +
            notRemoved.map(MirrorReport.Unclean::NotRemoved) +
            removalsDeclined.map(MirrorReport.Unclean::Kept) +
            held.filterNot { it.wasAsked() }.map(MirrorReport.Unclean::Held) +
            linksNotMade.map(MirrorReport.Unclean::LinkNotMade)
        return MirrorReport(
            copied,
            unchanged,
            removed,
            held,
            passedOver,
            failures,
            notRemoved,
            removalsDeclined,
            sourceEmpty = !yieldedAny,
            unclean,
        )
    }

    /** Why the walk passed over the entry at whose place this path of [dst] is, or below which, or null for none. */
    private fun Path.passedOverReason(): RemovalReason.AtPassedOver? =
        generateSequence(this) { it.parent }.firstNotNullOfOrNull { passedOverPlaces[it] }

    /** The removal of [relative], which no entry yields or holds. */
    private fun staleRemoval(relative: Path): Removal =
        Removal(relative, relative.passedOverReason() ?: RemovalReason.Stale)

    private fun passedOverAt(
        relative: Path,
        why: WalkSkip,
    ) {
        val reason = RemovalReason.AtPassedOver(why, relative)
        for (place in placeOf(relative)) passedOverPlaces[place] = reason
    }

    private fun place(
        placement: Placement,
        isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean,
        copy: (source: WalkEntry.File, out: Writer, name: String) -> EmptyResult<FileError>,
    ) {
        val entry = placement.entry
        val name = placement.name
        when (entry) {
            is WalkEntry.File -> Unit
            is WalkEntry.Link -> return placeLink(placement, entry)
            else -> {
                dst.createDirectories(name).onError { failed(entry, it) }
                return
            }
        }
        if (placement.found == FileType.Regular && dst.isCurrentAt(name, entry, isCurrent)) {
            unchanged += placement.mirrored()
            return
        }
        if (copy(entry, dst, name).failedFor(entry)) return
        copied += placement.mirrored()
    }

    /** Makes [link] at its output in [dst] with its target, unless a link with that target is there already. */
    private fun placeLink(
        placement: Placement,
        link: WalkEntry.Link,
    ) {
        if (placement.found == FileType.Symlink && dst.readSymlink(placement.name).getOrNull() == link.target) {
            unchanged += placement.mirrored()
            return
        }
        if (dst.switchSymlink(placement.name, link.target).failedFor(link)) return
        copied += placement.mirrored()
    }

    /** Removes [relative], which stands in an entry's way, from [dst], answering why that failed, or null. */
    private fun clear(relative: Path): FileError? = when (val deleted = dst.delete(relative.writerName())) {
        is Result.Success -> {
            removed += relative
            null
        }
        is Result.Error -> deleted.error
    }

    /** The error of the folder this output lies below that was not placed, or null when it lies below none. */
    private fun Path.blockedBy(blocked: Map<Path, FileError>): FileError? =
        if (blocked.isEmpty()) null else generateSequence(parent) { it.parent }.firstNotNullOfOrNull { blocked[it] }

    /**
     * Whether this entry the walk passed over holds what [dst] has at its place, as all but [dst] itself do; a link
     * leading to [dst] holds the entry at its own place.
     */
    private fun WalkEntry.Skipped.holds(): Boolean =
        why != WalkSkip.Excluded || path.oneName() != dst.root || isSymlink(path)

    /** Whether [keep] answers true for this entry of [dst], asked only about one no entry of the source keeps. */
    private fun WalkEntry.isKeptByCaller(): Boolean = relative !in kept && keep(relative)

    /** The placement that clears this path of [dst] or a folder it lies in, or null when none does. */
    private fun Path.clearedBy(): Placement? {
        if (cleared.isEmpty()) return null
        return generateSequence(this) { it.parent }.firstNotNullOfOrNull { cleared[it] }
    }

    /** The relative paths in [dst] at the place of the entry at [relative]: its own and its output as a file's. */
    private fun placeOf(relative: Path): List<Path> = listOf(relative, Path(outputName(relative)))

    /** Where the file at [relative] goes in [dst]: its own folder, under the last name [outputOf] gives it. */
    private fun outputName(relative: Path): String {
        val name = outputOf(relative).substringAfterLast('/')
        return relative.parent?.let { "${it.writerName()}/$name" } ?: name
    }

    private fun hold(relative: Path) {
        for (path in placeOf(relative)) {
            holds += path
            path.parent?.let { keepWithParents(it) }
        }
    }

    private fun keepWithParents(relative: Path) {
        var level: Path? = relative
        while (level != null) {
            kept += level
            level = level.parent
        }
    }

    /** Whether this failed, recording the failure against [entry] when it did. */
    private fun EmptyResult<FileError>.failedFor(entry: WalkEntry): Boolean {
        if (this !is Result.Error) return false
        failed(entry, error)
        return true
    }

    private fun failed(
        entry: WalkEntry,
        error: FileError,
    ) {
        failures += WalkFailure(entry.path, entry.relative, error)
    }
}

/**
 * Where one entry of the source goes: [entry] to the name [name], [output] as a path, in the destination, where [found]
 * stands now. [obstacle] is the failure [entry] is when what stands there, of another type, may not be removed; null
 * when nothing is in its way.
 */
private class Placement(
    val entry: WalkEntry,
    val name: String,
    val output: Path,
    val found: FileType?,
    val obstacle: FileError?,
) {
    /** What is removed before [entry] is placed, relative to the destination, in order. */
    var clearing: List<Path> = emptyList()

    /** Why what stands at [output] cannot all be removed, when reading it failed. */
    var unclearable: FileError? = null

    fun mirrored(): Mirrored = Mirrored(entry.relative, output)
}

/** Whether the walk passed over this entry because `exclude` or `enter` said so. */
private fun WalkEntry.Skipped.wasAsked(): Boolean = why == WalkSkip.Excluded || why == WalkSkip.Refused

/** Whether this entry the walk passed over holds nothing, so what the destination has at its place is stale. */
private fun WalkEntry.Skipped.holdsNothing(): Boolean = when (why) {
    WalkSkip.LeadsOutside, WalkSkip.Link, is WalkSkip.NotFileOrFolder, is WalkSkip.AlreadyEntered -> true
    else -> false
}

/** Whether this link's target leaves [source] by its names at any step, as an absolute target always does. */
private fun WalkEntry.Link.leadsOutOf(source: Path): Boolean =
    target.isAbsolute || linkedNamesBelow(source, path.parent ?: source, target) == null

/** Whether this path, or a folder it lies in, is named like a write's leftover. */
private fun Path.isInLeftover(): Boolean = generateSequence(this) { it.parent }.any { it.name.isLeftoverName() }

/** Whether this path lies strictly below one of [folders]. */
private fun Path.liesIn(folders: Set<Path>): Boolean =
    folders.isNotEmpty() && generateSequence(parent) { it.parent }.any { it in folders }

/** Whether the regular file at [name] is current for [source], as [isCurrent] answers. */
private fun Writer.isCurrentAt(
    name: String,
    source: WalkEntry.File,
    isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean,
): Boolean {
    val existing = info(name).getOrNull() ?: return false
    return isCurrent(source, existing)
}
