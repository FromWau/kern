package com.fromwau.kern.fs

/**
 * How [writeText], [writeBytes] and [copyTo] replace the content of a file that is already there. A new file is
 * written by rename in every mode but [InPlace], also through a symlink whose target is missing.
 *
 * The JVM on Windows reads no link count and no uid, so there [Auto] writes an existing file in place and
 * [Atomic] renames it. On Windows native, whether a rename replaces a file in one step is unverified, so [Auto]
 * writes an existing file in place, and ownership is not checked, so [Atomic] can hand another user's file to
 * this one. kotlinx-io cannot follow a link to an existing file there either, so [Auto] and [Replace] write through
 * such a link in place, keeping it, and [Atomic] refuses it with [AtomicRefusal.Symlink]. A link whose target is
 * missing is followed there as everywhere.
 */
public sealed interface WriteMode {
    /**
     * Renames when the file has no other hard links, belongs to this user, and the new file can be given its
     * group; writes in place when any of these is not so or cannot be read. The default.
     */
    public data object Auto : WriteMode

    /**
     * Always renames, and refuses with [FileError.AtomicRefused] a file it knows a rename would change: one with
     * other hard links, another owner, or a group the new file cannot be given. When the links or the owner cannot
     * be read, it renames.
     */
    public data object Atomic : WriteMode

    /**
     * Always renames, whatever that cuts off: other hard links keep the old content, another user's file becomes
     * this user's, and the group is kept when it can be. When it cannot, the new file gets the old one's
     * permissions without the group's, so a `660` file becomes `600`. A file this process may not read or write,
     * or a folder it may not write, is still refused as by every write.
     */
    public data object Replace : WriteMode

    /** Writes into the file itself, with a backup beside it, so its inode, owner and hard links stay. */
    public data object InPlace : WriteMode
}

/** What [copyTo] did: wrote its target, as [Written] says how, or left it [Unchanged]; [info] describes it now. */
public sealed interface Copied {
    /** The file this call left, as [info] would describe it. */
    public val info: FileInfo

    /** Nothing was written: [copyTo] was asked to copy a file onto itself, which [info] describes. */
    public data class Unchanged(override val info: FileInfo) : Copied
}

/**
 * How a write replaced the file's content, the [way], and the file it left: [info] is read once its content and
 * modification time are in and, for a rename, once it is renamed. Linux, Apple and Windows native read it from the
 * written file's own descriptor or handle, so a file another process puts at the path afterwards is never the one it
 * describes. The JVM and Android look the path up by name right after the write.
 *
 * ```kotlin
 * val written = notes.writeText(text).getOrElse { return warn(it) }
 * println("saved ${written.info.size} bytes, ${written.way}")
 * ```
 */
public data class Written(
    val way: WriteWay,
    override val info: FileInfo,
) : Copied

/** Which of the two ways a write replaced the file's content. */
public enum class WriteWay {
    /** A new file holding the content was renamed over the path, as [WriteMode] describes. */
    Renamed,

    /**
     * The content went into the file itself: by [WriteMode.InPlace], or by a mode that does not rename this file, as
     * [WriteMode] describes.
     */
    InPlace,
}
