package com.fromwau.kern.fs

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * The folder a write's file sits in, where [WritePlan] makes its temp and backups beside it, each named within it.
 * [PathFolder] reaches it by its path, as every `Path` call does. A [Writer] on Linux and iOS holds it open instead,
 * so a folder swapped for a link mid-write is never written through. Every call throws what the platform said.
 */
internal interface WriteFolder {
    /** The path [name] in this folder has, which errors and leftovers name. */
    fun pathOf(name: String): Path

    /** [openFileSource] of [name]. */
    fun openSource(
        name: String,
        streams: Boolean = false,
    ): FileSource

    /** [openFileSink] of [name]. */
    fun openSink(
        name: String,
        append: Boolean,
        permissions: Int? = null,
        newMode: Int = NEW_FILE_MODE,
    ): FileSink

    /** [createHeldFile] of [name], which [HeldFile.moveTo] renames within this folder. */
    fun createHeldFile(
        name: String,
        permissions: Int?,
        newMode: Int = NEW_FILE_MODE,
    ): HeldFile

    /** [permissionBits] of [name]. */
    fun permissionBits(name: String): Int?

    /** [copyGroup] from [from] to [to]. */
    fun copyGroup(
        from: String,
        to: String,
    ): Boolean

    /** [copyFile] from [from] to a new [to], made `600` with [ownerOnly] rather than carrying [from]'s permissions. */
    fun backUp(
        from: String,
        to: String,
        ownerOnly: Boolean,
    ): HeldFile

    /** Renames [from] to [to] in one step, replacing what is at [to]. */
    fun rename(
        from: String,
        to: String,
    )

    /** Removes the file [name]; one already gone is no failure. */
    fun delete(name: String)

    /** Whether nothing is at [name], as against a lookup that could not say which. */
    fun isGone(name: String): Boolean

    /** The names in this folder. */
    fun list(): List<String>

    /** [syncDirectory] of this folder. */
    fun sync(): Exception?
}

/** Removes [name] when it is there, and passes over a failure, for a copy whose removal changes no outcome. */
internal fun WriteFolder.deleteQuietly(name: String) {
    try {
        delete(name)
    } catch (_: Exception) {
        // A leftover copy changes nothing about the outcome already decided.
    }
}

/** [copyFile] within this folder, from [from] to a new [to]. */
internal fun WriteFolder.copyFile(
    from: String,
    to: String,
    ownerOnly: Boolean,
): HeldFile = createHeldFile(to, OWNER_READ_WRITE.takeIf { ownerOnly }).filledFrom(
    permissions = { if (ownerOnly) null else permissionBits(from) },
    open = { failingAt(FailedAt.LinkTarget(pathOf(from))) { openSource(from) } },
)

/**
 * A [WriteFolder] reached by its path, [folder], or the working directory when that is null. [keepGroup] stands in for
 * [copyGroup] and [backUpFile], when set, for [copyFile], so a test can make them fail. With [followLinks] false, a
 * link at a name it opens is refused where the platform can, rather than followed.
 */
internal class PathFolder(
    private val folder: Path?,
    private val keepGroup: (from: Path, to: Path) -> Boolean = ::copyGroup,
    private val backUpFile: ((from: Path, to: Path, ownerOnly: Boolean) -> HeldFile)? = null,
    private val followLinks: Boolean = true,
) : WriteFolder {
    override fun pathOf(name: String): Path = folder?.let { Path(it, name) } ?: Path(name)

    override fun openSource(
        name: String,
        streams: Boolean,
    ): FileSource = openFileSource(pathOf(name), followLinks, streams)

    override fun openSink(
        name: String,
        append: Boolean,
        permissions: Int?,
        newMode: Int,
    ): FileSink = openFileSink(pathOf(name), append, permissions, followLinks, newMode)

    override fun createHeldFile(
        name: String,
        permissions: Int?,
        newMode: Int,
    ): HeldFile = createHeldFile(pathOf(name), permissions, newMode)

    override fun permissionBits(name: String): Int? = permissionBits(pathOf(name))

    override fun copyGroup(
        from: String,
        to: String,
    ): Boolean = keepGroup(pathOf(from), pathOf(to))

    override fun backUp(
        from: String,
        to: String,
        ownerOnly: Boolean,
    ): HeldFile = backUpFile?.invoke(pathOf(from), pathOf(to), ownerOnly) ?: copyFile(from, to, ownerOnly)

    override fun rename(
        from: String,
        to: String,
    ) = SystemFileSystem.atomicMove(pathOf(from), pathOf(to))

    override fun delete(name: String) = SystemFileSystem.delete(pathOf(name), mustExist = false)

    override fun isGone(name: String): Boolean = pathOf(name).isGone()

    override fun list(): List<String> = SystemFileSystem.list(folder ?: Path(".")).map { it.name }

    override fun sync(): Exception? = syncDirectory(folder ?: Path("."))
}
