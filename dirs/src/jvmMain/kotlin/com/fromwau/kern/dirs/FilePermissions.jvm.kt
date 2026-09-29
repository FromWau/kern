package com.fromwau.kern.dirs

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
