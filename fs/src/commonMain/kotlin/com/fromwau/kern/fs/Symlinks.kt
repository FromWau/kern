package com.fromwau.kern.fs

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.map
import com.fromwau.kern.result.onError
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Which symlinks below the root [walkTopDown] follows. */
public enum class Symlinks {
    /**
     * A link, to a folder or a file, that leads to a place inside the root is followed as [Anywhere] follows it. That
     * is judged by where the link finally ends, so `state/a -> ../state/b` in a walk of `state` is followed to `b`. A
     * link leading anywhere else is passed over as [WalkSkip.LeadsOutside], so the walk never reads outside the
     * root's real folder. The default.
     */
    Inside,

    /** No link is followed: each one, whatever it leads to, is passed over as [WalkSkip.Link]. */
    NoLinks,

    /**
     * Every link is followed, wherever it leads. Each real folder is walked once, one inside the root under its own
     * path: a link pointing back up cannot repeat a tree, and a link `a` to a folder `c` beside it is passed over once
     * `c`'s files are walked as `c/x`, though `a` sorts first. A folder outside the root is walked under the link.
     */
    Anywhere,

    /**
     * No link is followed: each one, whatever it leads to, is yielded as a [WalkEntry.Link] holding its target as
     * written, so a backup can make the link again. [mirrorTo] recreates each one in its destination.
     */
    AsLinks,
}

/**
 * Whether this path is itself a symlink, without following it, so a link whose target is missing is one too.
 * Nothing at the path, not even a link, is [FileError.NotFound].
 */
public fun Path.isSymlink(): Result<Boolean, FileError> {
    refusedPath()?.let { return it }
    // Anything at the path that is not a link reads the same followed or not.
    return if (isSymlink(this)) Ok(true) else metadata().map { false }
}

/**
 * What this symlink holds, as it was written and without resolving it, so a relative target stays relative to the
 * link's folder.
 *
 * @return the target; [FileError.NotASymlink] naming what is there instead when this path is no symlink,
 *   [FileError.NotFound] when nothing is, or [FileError.Inaccessible] when it may not be looked up or read. On
 *   Windows native, which is unverified, a junction reads as a link too, and another kind of reparse point is
 *   [FileError.Inaccessible].
 */
public fun Path.readSymlink(): Result<Path, FileError> = readSymlinkWith(::readLink)

/** [readSymlink], with the link's content read by [read], which throws why it cannot be read. */
internal fun Path.readSymlinkWith(read: (Path) -> Path): Result<Path, FileError> {
    refusedPath()?.let { return it }
    if (!isSymlink(this)) return notASymlink()
    return try {
        Ok(read(this))
    } catch (e: Exception) {
        // Only looking again tells a link that went away, or was replaced, from one that cannot be read.
        if (isSymlink(this)) Err(inaccessible(this, e)) else notASymlink()
    }
}

/** What is at this path, which is no symlink, as [FileError.NotASymlink], or why it cannot be looked up. */
private fun Path.notASymlink(): Result<Nothing, FileError> =
    metadata().flatMap { Err(FileError.NotASymlink(this, typeOf(it))) }

/**
 * Makes this path a symlink to [target], stored as given: a relative [target] is read from this path's folder
 * whenever the link is followed, and a [target] that does not exist is allowed.
 *
 * ```kotlin
 * (dirs.data / "current").createSymlink(Path("release-42")).onError { println("no current release: $it") }
 * ```
 *
 * @return [FileError.AlreadyExists] for anything already at this path, which is never replaced;
 *   [FileError.WriteFailed] with the platform's cause when the link could not be made otherwise, such as for a missing
 *   folder to make it in, which is not created and is the cause [IoCause.NotFound]. Windows native, which is
 *   unverified, needs Developer Mode or the right to make links.
 */
@OptIn(InternalKernApi::class)
public fun Path.createSymlink(target: Path): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    target.refusedName()?.let { return it }
    val failure = createLink(this, target) ?: return Ok(Unit)
    // Asked of what is there rather than of the failure, whose cause the platform may not type.
    return Err(alreadyExistsOr { writeFailed(this, failure) })
}

/**
 * Points the symlink at this path at [target] in one step, so a reader following it finds the old target or the new
 * and never no link. A new link holding [target] is made beside this one as `.<name>.<id>.link` and moved onto this
 * path, which replaces a link as a link, also when [target] does not exist yet. Only a link is replaced: anything else
 * at this path is refused before the new link is made. With nothing at this path, the link is made there, and the
 * missing folders on the way are created as [createDirectories] makes them.
 *
 * ```kotlin
 * (dirs.data / "current").switchSymlinkTo(Path("release-43")).onError { println("still on the old release: $it") }
 * ```
 *
 * A switch cut short by a killed process leaves the new link beside this one, which [leftovers] finds as a
 * [Leftover.StagedLink]; one that fails otherwise removes it again.
 *
 * @param target what the link holds, stored as given: a relative [target] is read from this path's folder.
 * @return `Ok` once the link holds [target]; [FileError.NotRegularFile] naming this path for a folder at it, and
 *   [FileError.AlreadyExists] for a regular file or anything else there that is no link; [FileError.NameTooLong]
 *   naming [LongName.StagedLink] when this path's name is too long for the new link's, more than
 *   [MAX_STAGED_NAME_BYTES]; what creating the folders or [createSymlink] answers; or [FileError.WriteFailed] naming
 *   this path, with the new link as its [FailedAt.Temp], when the new link could not be moved into place.
 */
public fun Path.switchSymlinkTo(target: Path): EmptyResult<FileError> = switchSymlinkWith(target, ::moveLink)

/** [switchSymlinkTo], with the new link put in place by [moveLink], which answers why it could not, or null. */
internal fun Path.switchSymlinkWith(
    target: Path,
    moveLink: (link: Path, onto: Path) -> Exception?,
): EmptyResult<FileError> {
    refusedPath()?.let { return it }
    target.refusedName()?.let { return it }
    val staged = besideWith(STAGED_LINK_SUFFIX)
    tooLongToStage(staged, LongName.StagedLink)?.let { return it }
    notALinkHere()?.let { return it }
    val created = parent?.let { makeFolders(it) }
    if (created is Result.Error) return created
    staged.createSymlink(target).onError { return Err(it) }
    val failure = moveLink(staged, this) ?: return Ok(Unit)
    val _ = staged.delete()
    return Err(writeFailed(this, failure, at = FailedAt.Temp(staged)))
}

/**
 * What [switchSymlinkTo] answers for something at this path that is no link: [FileError.NotRegularFile] for a folder,
 * which a link is never moved onto, and [FileError.AlreadyExists] for anything else. Null for a link, for nothing, and
 * for a path that cannot be looked up, which the switch's next steps then answer for.
 */
private fun Path.notALinkHere(): Err<FileError>? = when (val there = fileType(followLinks = false).getOrNull()) {
    null, FileType.Symlink -> null
    FileType.Directory -> Err(FileError.NotRegularFile(this, FileType.Directory))
    else -> Err(FileError.AlreadyExists(this, there))
}

/**
 * [FileError.AlreadyExists] for this path, with what is there read without following a link, or [FileType.Other]
 * when it can no longer be looked up; for kern's modules that make something without replacing what is there.
 */
@InternalKernApi
public fun Path.alreadyExists(): FileError.AlreadyExists =
    FileError.AlreadyExists(this, fileType(followLinks = false).getOrNull() ?: FileType.Other)

/** [FileError.AlreadyExists] when anything is at this path, a link to nothing included, else [otherwise]. */
internal inline fun Path.alreadyExistsOr(otherwise: () -> FileError): FileError =
    when (val there = fileType(followLinks = false)) {
        is Result.Success -> FileError.AlreadyExists(this, there.value)
        is Result.Error -> otherwise()
    }

/** Makes [path] a symlink holding [target] as given, without replacing anything; null, or the failure that kept it. */
internal expect fun createLink(
    path: Path,
    target: Path,
): Exception?

/** Whether [path] is itself a symlink, rather than what it points at. */
internal expect fun isSymlink(path: Path): Boolean

/** What the symlink [path] holds, as written, throwing why it cannot be read, such as it being no symlink. */
internal expect fun readLink(path: Path): Path

/** Where [target], as the link at [link] holds it, leads: a relative [target] is read from the link's folder. */
internal fun linkedPath(
    link: Path,
    target: Path,
): Path = if (target.isAbsolute) target else link.parent?.let { Path(it, target.toString()) } ?: target

/** Where the links starting at this path lead, the last path of its [linkChain], or null when the chain goes round. */
@OptIn(InternalKernApi::class)
internal fun Path.linkChainEnd(): Path? = linkChain()?.last()

/**
 * Every path the links starting at this path pass through, in order: this path, each link's target joined to the
 * link's folder, and last the first path in the chain that is no symlink, or that cannot be read as one. Null when the
 * chain goes round, which it does once it holds more than 40 links, as many as Linux follows in one lookup. For kern's
 * modules that watch where a link leads.
 */
@InternalKernApi
public fun Path.linkChain(): List<Path>? {
    val chain = mutableListOf(this)
    repeat(LINKS_FOLLOWED + 1) {
        val current = chain.last()
        val next = try {
            readLink(current)
        } catch (_: Exception) {
            return chain
        }
        chain += linkedPath(current, next)
    }
    return null
}

/**
 * Whether looking this path up follows more than [LINKS_FOLLOWED] symlinks, at its last name and in the folders above
 * it, as a loop does. Each link is read and followed here, so the answer needs no cause from the platform.
 */
internal fun Path.linksGoRound(): Boolean {
    val start = try {
        fromWorkingDirectory()
    } catch (_: Exception) {
        return false
    }
    val names = ArrayDeque(start.names())
    var current = start.root()
    var followed = 0
    while (names.isNotEmpty()) {
        val next = Path(current, names.removeFirst())
        // Nothing in current is a link, so a read of next that fails means next is no link, or not there.
        val target = try {
            readLink(next)
        } catch (_: Exception) {
            current = next
            continue
        }
        if (++followed > LINKS_FOLLOWED) return true
        names.addAll(0, target.names())
        if (target.isAbsolute) current = target.root()
    }
    return false
}

/** The names this path is made of, from its root or its first name on. */
internal fun Path.names(): List<String> = generateSequence(this) { it.parent }
    .map { it.name }
    .filter { it.isNotEmpty() }
    .toList()
    .asReversed()

/** The root this absolute path starts from. */
private fun Path.root(): Path = generateSequence(this) { it.parent }.last()

/** As many links as Linux follows in one lookup. */
internal const val LINKS_FOLLOWED = 40

/** Removes the symlink [path] itself, whatever it points at. Null when it is gone, or the failure that kept it. */
internal expect fun deleteLink(path: Path): Exception?

/**
 * Moves the symlink [path] itself to [target] in one step, whatever it points at, replacing a file or link at
 * [target]. Null once moved, or the failure that kept it.
 */
internal expect fun moveLink(
    path: Path,
    target: Path,
): Exception?

/**
 * The absolute path of what this path reaches, with every symlink on the way resolved, so two spellings of one
 * file or folder give one answer.
 *
 * @return the resolved path; [FileError.NotFound] when nothing is there, or [FileError.Inaccessible] when it may
 *   not be looked up.
 */
public fun Path.realPath(): Result<Path, FileError> = metadata().flatMap {
    try {
        Ok(realPath(this))
    } catch (e: Exception) {
        inaccessibleUnlessGone(e)
    }
}

/**
 * The absolute path this path names, as [realPath] gives it, also when its last parts do not exist yet: the longest
 * part that exists is resolved, links followed, and the missing rest is appended by name, without `.` and with each
 * `..` taking back the name before it. That `..` is applied by name only, since nothing it follows exists to resolve.
 * A relative path is taken from the working directory. Nothing is created.
 *
 * ```kotlin
 * Path("~/notes/../todo.txt").expandTilde(home).canonicalPath()   // /home/me/todo.txt, whether or not it exists
 * ```
 *
 * @return the absolute path; [FileError.NotFound] for an empty path, or for a missing rest below a regular file, as a
 *   lookup of such a path answers; [FileError.Inaccessible] for a part that may not be looked up, or a symlink loop.
 */
public fun Path.canonicalPath(): Result<Path, FileError> {
    refusedPath()?.let { return it }
    val absolute = try {
        fromWorkingDirectory()
    } catch (e: Exception) {
        return Err(inaccessible(Path("."), e))
    }
    val missing = ArrayDeque<String>()
    var level = absolute
    while (true) {
        when (val looked = level.metadata()) {
            is Result.Success -> {
                if (missing.isNotEmpty() && !looked.value.isDirectory) return Err(FileError.NotFound(this))
                return level.realPath().map { it.withNamesAppended(missing) }
            }
            is Result.Error -> if (looked.error !is FileError.NotFound) return looked
        }
        missing.addFirst(level.name)
        // A root that is missing leaves nothing that exists to start from.
        level = level.parent ?: return Err(FileError.NotFound(this))
    }
}

/** This path, taken from the working directory when relative, throwing why the working directory cannot be had. */
private fun Path.fromWorkingDirectory(): Path =
    if (isAbsolute) this else Path(SystemFileSystem.resolve(Path(".")), toString())

/** This path with [names] appended, leaving out `.` and taking back the name before each `..`. */
private fun Path.withNamesAppended(names: List<String>): Path {
    var base = this
    val kept = mutableListOf<String>()
    for (name in names) {
        when (name) {
            "", "." -> Unit
            ".." -> if (kept.isNotEmpty()) kept.removeAt(kept.lastIndex) else base = base.parent ?: base
            else -> kept += name
        }
    }
    return if (kept.isEmpty()) base else Path(base, *kept.toTypedArray())
}

/** One absolute name for what [path] reaches with every link on the way followed, throwing why it cannot be had. */
internal expect fun realPath(path: Path): Path

/** [realPath], or null when it cannot be had. */
internal fun realPathOrNull(path: Path): Path? = try {
    realPath(path)
} catch (_: Exception) {
    null
}

/** One spelling of this path, so the same file reached through a link and named directly compare equal. */
internal fun Path.oneName(): Path = realPathOrNull(this) ?: this

/** Whether this path is [folder] or below it, by name alone, as two resolved paths compare. */
internal fun Path.isAtOrBelow(folder: Path): Boolean = generateSequence(this) { it.parent }.any { it == folder }

/**
 * [path] without the `\\?\` prefix Windows puts on a final path name, so `\\?\C:\x` reads `C:\x` and
 * `\\?\UNC\server\share\x` reads `\\server\share\x`, the way the same path is usually spelled.
 */
internal fun withoutVerbatimPrefix(path: String): String = when {
    path.startsWith(VERBATIM_UNC_PREFIX) -> "\\\\" + path.removePrefix(VERBATIM_UNC_PREFIX)
    else -> path.removePrefix(VERBATIM_PREFIX)
}

private const val VERBATIM_PREFIX = "\\\\?\\"

private const val VERBATIM_UNC_PREFIX = "\\\\?\\UNC\\"
