package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.Path as NioPath

internal actual fun copyPermissions(
    from: Path,
    to: Path,
) {
    // A filesystem that has no posix permissions has none to copy, which is what Windows answers here: a
    // file there takes the access rules of the folder it is made in, and the copy is made beside the file.
    val view = Files.getFileAttributeView(NioPath.of(from.toString()), PosixFileAttributeView::class.java)
        ?: return
    Files.setPosixFilePermissions(NioPath.of(to.toString()), view.readAttributes().permissions())
}

// Without the unix view, as on Windows, a file has no group id to keep.
internal actual fun copyGroup(
    from: Path,
    to: Path,
): Boolean {
    if (!hasUnixView) return true
    return try {
        val group = Files.getAttribute(NioPath.of(from.toString()), "unix:gid")
        val copy = NioPath.of(to.toString())
        if (Files.getAttribute(copy, "unix:gid") != group) Files.setAttribute(copy, "unix:gid", group)
        true
    } catch (_: Exception) {
        false
    }
}
