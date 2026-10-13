package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Paths

// A name java.nio cannot parse, such as one holding a NUL, can be no link.
internal actual fun isSymlink(path: Path): Boolean = try {
    Files.isSymbolicLink(Paths.get(path.toString()))
} catch (_: InvalidPathException) {
    false
}

internal actual fun deleteLink(path: Path): String? = try {
    Files.delete(Paths.get(path.toString()))
    null
} catch (e: FileSystemException) {
    e.fault
} catch (e: Exception) {
    e.reason
}

internal actual fun realPath(path: Path): Path = resolvedOrSelf(path)
