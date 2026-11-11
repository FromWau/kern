package com.fromwau.kern.fs

import kotlinx.io.files.Path

/** What decides whether a write may replace a file with a renamed copy without losing anything. */
internal data class FileIdentity(
    val linkCount: Long,
    val ownedByCurrentUser: Boolean,
    val carriesAttributes: Boolean = false,
) {
    val hasOtherLinks: Boolean get() = linkCount > 1
}

/**
 * Gives [to] the ACL and extended attributes of [from], following symlinks, and takes off any [from] does not have.
 * False when one could not be read, set or taken off.
 */
internal expect fun copyAttributes(
    from: Path,
    to: Path,
): Boolean

/** [path]'s link count and whether this process's user owns it, following symlinks; null when it cannot be read. */
internal expect fun fileIdentity(path: Path): FileIdentity?

/** Whether [first] and [second] reach one file, following symlinks; false when either cannot be looked up. */
internal expect fun isSameFile(
    first: Path,
    second: Path,
): Boolean

/**
 * Forces [directory]'s entries, such as a rename just made in it, to disk where the platform can. Null on success
 * or where it cannot be done.
 */
internal expect fun syncDirectory(directory: Path): Exception?

/** Whether a rename over an existing file is known to replace it in one step on this platform. */
internal expect val renameReplacesAtomically: Boolean
