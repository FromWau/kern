package com.fromwau.kern.dirs

import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** Takes away read and traverse permission, so nothing under [path] can be reached. */
internal fun shutOut(path: Path) {
    File(path.toString()).apply {
        setReadable(false)
        setExecutable(false)
    }
}

internal fun restore(path: Path) {
    File(path.toString()).apply {
        setReadable(true)
        setExecutable(true)
    }
}

/** Whether this runner can still look the path up, which root can whatever the permissions say. */
internal fun canLookUp(path: Path): Boolean = runCatching {
    Files.readAttributes(Paths.get(path.toString()), BasicFileAttributes::class.java)
}.isSuccess

/** Whether this runner can still read the directory, which root can whatever the permissions say. */
internal fun canList(path: Path): Boolean = File(path.toString()).list() != null
