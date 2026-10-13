package com.fromwau.kern.fs

/**
 * How [writeText] and [writeBytes] replace the content of a file that is already there. A new file is written by
 * rename in every mode but [InPlace].
 *
 * The JVM on Windows reads no link count and no uid, so there [Auto] writes an existing file in place and
 * [Atomic] renames it. On Windows native, whether a rename replaces a file in one step is unverified, so [Auto]
 * writes an existing file in place, and ownership is not checked, so [Atomic] can hand another user's file to
 * this one. kotlinx-io cannot follow links there either, so [Auto] writes through a symlink in place and [Atomic]
 * refuses it.
 */
public sealed interface WriteMode {
    /**
     * Renames when the file has no other hard links, belongs to this user, and the new file can be given its
     * group; writes in place when any of these is not so or cannot be read. The default.
     */
    public data object Auto : WriteMode

    /**
     * Always renames, and refuses with [FileError.WriteFailed] a file it knows a rename would change: one with
     * other hard links, another owner, or a group the new file cannot be given, and a symlink whose target cannot
     * be resolved. When the links or the owner cannot be read, it renames.
     */
    public data object Atomic : WriteMode

    /** Writes into the file itself, with a backup beside it, so its inode, owner and hard links stay. */
    public data object InPlace : WriteMode
}
