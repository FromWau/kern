package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlinx.io.files.Path

/** The entry [name] in [level], where a [Writer]'s walk to a name ended; `.` when it ended on the folder itself. */
internal class Spot(
    val level: Level,
    val name: String,
)

/** What a walk does with the last name it is given. */
internal enum class LastName {
    /** Acts on the entry itself, so a link there is never followed. */
    Entry,

    /** Reads what is there, so a link there is followed while it stays below the boundary. */
    Followed,

    /** Enters it as a folder, made when missing where the walk makes folders, a link there followed as on the way. */
    Folder,
}

/**
 * One call's view of a [Writer]: the folders from its boundary down to its root, [base], opened without following a
 * link and checked against the writer's record. Every folder a walk opens stays open until [close].
 */
internal class WriterSession(
    private val writer: Writer,
    private val base: List<Level>,
) : AutoCloseable {
    private val opened = base.toMutableList()

    /** The root, held open. */
    val rootLevel: Level get() = base.last()

    /**
     * Walks [name], a relative path [relativePathProblem] took, from the root one name at a time, as [last] says for
     * the last one, making a missing folder on the way when [makeFolders] says so, with [folderMode], and the folder
     * [LastName.Folder] ends on with [lastFolderMode]. A link on
     * the way is followed only while every folder it passes stays at or below the boundary; one that leads out is
     * [FileError.Escapes]. With [trusted], a folder from the boundary down to the root, or one it enters, that someone
     * else may change is [FileError.Refused]. Given [unmade], a missing folder is not made but passed by name, and the
     * names passed that way below the spot's folder are added to it.
     */
    fun walk(
        name: String,
        last: LastName,
        makeFolders: Boolean,
        folderMode: Int = FOLDER_MODE,
        trusted: Boolean = false,
        unmade: MutableList<String>? = null,
        lastFolderMode: Int = folderMode,
    ): Result<Spot, FileError> {
        val asked = writer.root / name
        val stack = base.toMutableList()
        val names = ArrayDeque(name.split('/'))
        var link: FollowedLink? = null
        var followed = 0
        return try {
            if (trusted) base.firstNotNullOfOrNull { it.untrusted(asked) }?.let { return Err(it) }
            while (names.isNotEmpty()) {
                val next = names.removeFirst()
                val level = stack.last()
                if (next.isEmpty() || next == ".") continue
                val isLast = names.isEmpty()
                if (!unmade.isNullOrEmpty()) {
                    // Below a missing folder nothing is there to look at, so each name is taken as it reads.
                    when {
                        next == ".." -> unmade.removeAt(unmade.lastIndex)
                        isLast && last != LastName.Folder -> return Ok(Spot(level, next))
                        else -> unmade += next
                    }
                    continue
                }
                if (next == "..") {
                    if (stack.size == 1) return Err(escapes(asked, link))
                    stack.removeAt(stack.lastIndex)
                    continue
                }
                if (isLast && last != LastName.Folder) {
                    if (last == LastName.Entry || level.typeOf(next) != FileType.Symlink) {
                        return Ok(Spot(level, next))
                    }
                } else {
                    val mode = if (isLast) lastFolderMode else folderMode
                    val entered = when (val step = enter(level, next, asked, makeFolders, mode)) {
                        is Step.Entered -> step.level
                        is Step.Failed -> {
                            if (unmade == null || step.error !is FileError.NotFound) return Err(step.error)
                            unmade += next
                            continue
                        }
                        Step.Link -> null
                    }
                    if (entered != null) {
                        if (trusted) entered.untrusted(asked)?.let { return Err(it) }
                        stack += entered
                        continue
                    }
                }
                if (++followed > LINKS_FOLLOWED) return Err(FileError.Inaccessible(asked, IoCause.Loop, null))
                val target = level.readLink(next)
                link = FollowedLink(linkedPath(level.pathOf(next), target), toFile = isLast && last != LastName.Folder)
                val rest = if (target.isAbsolute) {
                    val below = target.segments().belowOrNull(writer.boundary.segments())
                        ?: return Err(escapes(asked, link))
                    stack.subList(1, stack.size).clear()
                    below
                } else {
                    target.names()
                }
                names.addAll(0, rest)
            }
            Ok(Spot(stack.last(), "."))
        } catch (e: Exception) {
            Err(inaccessible(asked, e))
        }
    }

    /**
     * The real path the entry [name] gets once a walk made the missing folders on the way, found without making any;
     * or what that walk answers.
     */
    fun plannedEntry(name: String): Result<Path, FileError> {
        val unmade = mutableListOf<String>()
        return when (val walked = walk(name, LastName.Entry, makeFolders = false, unmade = unmade)) {
            is Result.Success -> Ok(Path(walked.value.level.path, *unmade.toTypedArray(), walked.value.name))
            is Result.Error -> walked
        }
    }

    /** The folder [walk] with [LastName.Folder] reaches at [name], held open. */
    fun folder(
        name: String,
        makeFolders: Boolean,
        folderMode: Int = FOLDER_MODE,
        lastFolderMode: Int = folderMode,
    ): Result<Level, FileError> = when (
        val walked = walk(name, LastName.Folder, makeFolders, folderMode, lastFolderMode = lastFolderMode)
    ) {
        is Result.Success -> Ok(walked.value.level)
        is Result.Error -> walked
    }

    /**
     * Opens the folder [name] in [level], made first with [folderMode] when missing and [makeFolders] says so, or
     * [Step.Link] for a link there, which the walk follows itself. A folder swapped in between two looks is looked at
     * again.
     */
    private fun enter(
        level: Level,
        name: String,
        asked: Path,
        makeFolders: Boolean,
        folderMode: Int,
    ): Step {
        repeat(LOOKS_PER_NAME) {
            try {
                return Step.Entered(level.child(name).also { opened += it })
            } catch (_: LevelMoved) {
                // Looked at below, since a missing folder, a link and a file each go on differently.
            }
            when (val there = level.typeOf(name)) {
                null -> {
                    if (!makeFolders) return Step.Failed(FileError.NotFound(asked))
                    try {
                        val _ = level.makeFolder(name, folderMode)
                    } catch (e: Exception) {
                        return Step.Failed(writeFailed(asked, e, at = FailedAt.Folder(level.pathOf(name))))
                    }
                }
                FileType.Symlink -> return Step.Link
                FileType.Directory -> Unit
                else -> return Step.Failed(FileError.NotADirectory(level.pathOf(name), there))
            }
        }
        return Step.Failed(FileError.Inaccessible(asked, IoCause.Other, LevelMoved(level.pathOf(name))))
    }

    /**
     * [FileError.Escapes] for [asked], whose walk left the boundary by way of [link]: resolved to the real path of the
     * folder that link names, or by name alone to its target when it stood at the last name or has no real path.
     */
    private fun escapes(
        asked: Path,
        link: FollowedLink?,
    ): FileError.Escapes {
        if (link != null && link.toFile) return FileError.Escapes(asked, writer.boundary, link.target.byNames())
        val leadsTo = link?.target ?: asked
        val resolved = leadsTo.canonicalPath().getOrNull() ?: leadsTo.byNames()
        return FileError.Escapes(asked, writer.boundary, resolved)
    }

    override fun close() {
        // Each folder opened once, the base included, and closed whatever another close throws.
        for (level in opened) {
            try {
                level.close()
            } catch (_: Exception) {
                // A descriptor that will not close holds nothing this call still needs.
            }
        }
    }
}

/** A link a walk followed, to [target], and whether it stood at the last name as a file rather than a folder. */
private class FollowedLink(
    val target: Path,
    val toFile: Boolean,
)

/** What [WriterSession.walk] made of one name on the way. */
private sealed interface Step {
    class Entered(val level: Level) : Step

    data object Link : Step

    class Failed(val error: FileError) : Step
}

/**
 * Where [target], a link's target read from the folder [from], leads by its names alone, as the names below
 * [boundary]; null when it steps above [boundary] at any point or is an absolute path not below it, as a walk refuses
 * such a link when it follows one.
 */
internal fun linkedNamesBelow(
    boundary: Path,
    from: Path,
    target: Path,
): List<String>? {
    val boundaryNames = boundary.segments()
    val names = if (target.isAbsolute) {
        target.segments().belowOrNull(boundaryNames)
    } else {
        from.segments().belowOrNull(boundaryNames)?.plus(target.names())
    }
    val below = mutableListOf<String>()
    for (name in names ?: return null) {
        when (name) {
            "", "." -> Unit
            ".." -> below.removeLastOrNull() ?: return null
            else -> below += name
        }
    }
    return below
}

/** These segments after [prefix], when they start with it, or null. */
internal fun List<String>.belowOrNull(prefix: List<String>): List<String>? =
    if (size >= prefix.size && subList(0, prefix.size) == prefix) drop(prefix.size) else null

/** How often a walk looks at one name that keeps changing between a folder and something else before giving up. */
internal const val LOOKS_PER_NAME = 8
