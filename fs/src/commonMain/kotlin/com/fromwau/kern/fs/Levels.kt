package com.fromwau.kern.fs

import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * One folder a [Writer] has opened on its way to a name, [path] being its real path. Every name a call takes is one
 * entry in this folder, and a symlink at it is never followed: the writer follows one only where it stays below its
 * boundary. Linux and iOS hold the folder open, so a folder on the way swapped for a link after it was opened is never
 * entered; elsewhere it is reached by its path. Every call throws what the platform said.
 */
internal interface Level : WriteFolder, AutoCloseable {
    /** This folder's real path, which no link leads through. */
    val path: Path

    /** [info] of this folder itself. */
    fun info(): FileInfo

    /** What tells this folder from any other, as [fileKeyOrNull] reads it, or null where the platform cannot tell. */
    fun key(): Any?

    /** What is at [name] itself, a link being [FileType.Symlink], or null when nothing is. */
    fun typeOf(name: String): FileType?

    /** [info] of what is at [name] itself, a link there described as the link. */
    fun infoOf(name: String): FileInfo

    /** The folder at [name], never through a link; [LevelMoved] when no folder is there by now. */
    fun child(name: String): Level

    /** Makes the folder [name] with [mode], as [makeFolder] takes it; false when a folder is already there. */
    fun makeFolder(
        name: String,
        mode: Int,
    ): Boolean

    /** What the symlink at [name] holds, as written. */
    fun readLink(name: String): Path

    /** Makes [name] a symlink holding [target], without replacing anything. */
    fun createLink(
        name: String,
        target: Path,
    )

    /** Removes what is at [name] itself: the empty folder there when [folder], otherwise the file or link. */
    fun remove(
        name: String,
        folder: Boolean,
    )

    /** Moves what is at [from] itself to [toName] in [to], in one step, replacing a file or link there. */
    fun move(
        from: String,
        to: Level,
        toName: String,
    )

    /** [renameNoReplace] from [from] to [to] in this folder. */
    fun renameNoReplace(
        from: String,
        to: String,
    ): NoReplaceRename

    /** [openAppendFile] of [name], which is a regular file or nothing. */
    fun openAppend(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): Result<AppendFile, FileError>

    /** [tryOsLock] of the lock file [name], which is a regular file or nothing. */
    fun tryLock(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): OsLock
}

/** What a [Level] looked up is not, by now, a folder reached without a link: it was moved, removed or replaced. */
internal class LevelMoved(path: Path) : Exception("$path is no longer the folder it was")

/**
 * The folder at [path], a real path, opened as a [Level] the way this platform opens one: name by name from the root
 * without following a link on Linux and iOS. Throws [LevelMoved] when no folder is at [path] reached without a link
 * by now, or what the platform said.
 */
internal expect fun openLevel(path: Path): Level

/**
 * A [Level] reached by its [path], where kern has no call that opens a name in an open folder: the JVM, Android,
 * macOS and Windows native. A folder on the way that is swapped for a link between two calls is followed.
 */
internal class PathLevel(override val path: Path) :
    Level,
    WriteFolder by PathFolder(path, followLinks = false) {
    override fun info(): FileInfo = fileInfoAt(path)

    override fun key(): Any? = fileKeyOrNull(path)

    override fun typeOf(name: String): FileType? = ownFileTypeAt(pathOf(name))

    override fun infoOf(name: String): FileInfo = ownFileInfoAt(pathOf(name))

    override fun child(name: String): Level {
        val folder = pathOf(name)
        if (ownFileTypeAt(folder) != FileType.Directory) throw LevelMoved(folder)
        return PathLevel(folder)
    }

    override fun makeFolder(
        name: String,
        mode: Int,
    ): Boolean = makeFolder(pathOf(name), mode)

    override fun readLink(name: String): Path = readLink(pathOf(name))

    override fun createLink(
        name: String,
        target: Path,
    ) {
        createLink(pathOf(name), target)?.let { throw it }
    }

    override fun remove(
        name: String,
        folder: Boolean,
    ) {
        val entry = pathOf(name)
        if (isSymlink(entry)) {
            deleteLink(entry)?.let { throw it }
            return
        }
        try {
            SystemFileSystem.delete(entry)
        } catch (e: Exception) {
            whyNotDeleted(entry, e)?.let { throw it }
        }
    }

    override fun move(
        from: String,
        to: Level,
        toName: String,
    ) {
        moveLink(pathOf(from), to.pathOf(toName))?.let { throw it }
    }

    override fun renameNoReplace(
        from: String,
        to: String,
    ): NoReplaceRename = renameNoReplace(pathOf(from), pathOf(to))

    override fun list(): List<String> = SystemFileSystem.list(path).map { it.name }

    override fun openAppend(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): Result<AppendFile, FileError> = openAppendFile(pathOf(name), permissions, newMode)

    override fun tryLock(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): OsLock = tryOsLock(pathOf(name), permissions, newMode)

    override fun close() = Unit
}

/** [openLevel] by path, for the platforms whose [Level] is a [PathLevel]. */
internal fun openPathLevel(path: Path): Level {
    val real = try {
        realPath(path)
    } catch (e: Exception) {
        if (e.ioCause() == IoCause.NotFound) throw LevelMoved(path)
        throw e
    }
    if (real != path || ownFileTypeAt(path) != FileType.Directory) throw LevelMoved(path)
    return PathLevel(path)
}
