package com.fromwau.kern.fs

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/** Whether [walkTopDown] enters a folder it reaches through a symlink. */
public enum class Symlinks {
    /** A linked folder is not entered; a link to a file is still yielded, since it is a file to read. */
    Skip,

    /**
     * A linked folder is entered. Each real folder is walked once, under the first path that reaches it: a link
     * pointing back up cannot repeat a tree, and a link `a` to a folder `c` beside it walks `c`'s files as `a/x`,
     * since `a` comes first, and passes over `c`.
     */
    Follow,
}

/** Whether [path] is itself a symlink, rather than what it points at. */
internal expect fun isSymlink(path: Path): Boolean

/** Removes the symlink [path] itself, whatever it points at, or says why it could not. Null when it is gone. */
internal expect fun deleteLink(path: Path): String?

/** One name for the entry [path] reaches with every link on the way followed, or [path] when it cannot be had. */
internal expect fun realPath(path: Path): Path

/** [realPath] where kotlinx-io's resolve follows links. */
internal fun resolvedOrSelf(path: Path): Path = try {
    SystemFileSystem.resolve(path)
} catch (_: Exception) {
    path
}
