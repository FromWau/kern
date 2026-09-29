package com.fromwau.kern.dirs

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

/** The bits of a mode that say who may do what: nine for owner, group and other, and the three above them. */
internal const val PERMISSION_BITS = 0b111_111_111_111
