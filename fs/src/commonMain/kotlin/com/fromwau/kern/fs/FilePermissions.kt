package com.fromwau.kern.fs

import kotlinx.io.files.Path

/**
 * Puts [from]'s own permissions on [to], so a copy is readable by no one the file itself is not: a new file
 * gets the writing process's default permissions, which a `600` file's would not be. A failed write moves the
 * copy back, so what it carries is what the file is left holding.
 *
 * Throws where a platform has permissions and they could not be read or set, which a write then reports like
 * any other failure to make the copy. A platform that has none does nothing.
 */
internal expect fun copyPermissions(
    from: Path,
    to: Path,
)

/**
 * Hands [to] to [from]'s group, so a renamed copy keeps who may share the file. Answers false when that could
 * not be done, and true where it was already so or a platform has no groups to keep.
 */
internal expect fun copyGroup(
    from: Path,
    to: Path,
): Boolean

/** The bits of a mode that say who may do what: nine for owner, group and other, and the three above them. */
internal const val PERMISSION_BITS = 0b111_111_111_111
