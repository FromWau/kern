package com.fromwau.kern.fs

import kotlinx.io.files.Path

/** What decides whether a write may replace a file with a renamed copy without losing anything. */
internal data class FileIdentity(
    val linkCount: Long,
    val ownedByCurrentUser: Boolean,
) {
    val hasOtherLinks: Boolean get() = linkCount > 1
}

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
