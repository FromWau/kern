package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun makeFolder(
    path: Path,
    mode: Int,
): Boolean = try {
    val folder = Paths.get(path.toString())
    if (hasPosixView) {
        Files.createDirectory(folder, createdWithMode(mode))
    } else {
        Files.createDirectory(folder)
    }
    true
} catch (e: FileAlreadyExistsException) {
    if (isFolderAt(path)) false else throw e
}
