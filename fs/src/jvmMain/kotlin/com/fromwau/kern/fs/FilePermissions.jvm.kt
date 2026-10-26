package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.Path as NioPath

// A filesystem that has no posix permissions has none to read or set, which is what Windows answers here: a file
// there takes the access rules of the folder it is made in, and a copy is made beside the file.
internal actual fun permissionBits(path: Path): Int? = posixView(path)
    ?.readAttributes()
    ?.permissions()
    ?.sumOf { it.bit }

internal actual fun setFilePermissions(
    path: Path,
    bits: Int,
) {
    posixView(path)?.setPermissions(posixPermissionsOf(bits))
}

private fun posixView(path: Path): PosixFileAttributeView? =
    Files.getFileAttributeView(NioPath.of(path.toString()), PosixFileAttributeView::class.java)

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
