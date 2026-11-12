package com.fromwau.kern.fs.coroutines

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

/** The folder's file key, or its creation time where the file system has no keys, as on Windows. */
internal actual fun Path.folderIdentity(): Any? = try {
    val attributes = Files.readAttributes(Paths.get(toString()), BasicFileAttributes::class.java)
    attributes.fileKey() ?: attributes.creationTime()
} catch (_: Exception) {
    null
}
