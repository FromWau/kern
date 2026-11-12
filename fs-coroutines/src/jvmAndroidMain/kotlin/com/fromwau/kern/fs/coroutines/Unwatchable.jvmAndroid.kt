package com.fromwau.kern.fs.coroutines

import com.fromwau.kern.fs.PseudoFileSystem
import kotlinx.io.files.Path
import java.io.IOException
import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Paths

internal actual fun pseudoFileSystemOf(path: Path): PseudoFileSystem? {
    val store = generateSequence(path) { it.parent }.firstNotNullOfOrNull { it.fileStore() } ?: return null
    return when (store.type()) {
        "proc" -> PseudoFileSystem.Proc
        "sysfs" -> PseudoFileSystem.Sys
        else -> null
    }
}

private fun Path.fileStore(): FileStore? = try {
    Files.getFileStore(Paths.get(toString()))
} catch (_: IOException) {
    null
}
