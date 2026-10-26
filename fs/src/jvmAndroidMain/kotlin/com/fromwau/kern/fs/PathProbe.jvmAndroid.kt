package com.fromwau.kern.fs

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes

// java.io.File, which kotlinx-io looks through here, answers false for a refused lookup exactly as it does for a
// missing file, while java.nio tells them apart by exception. Paths.get, not Path.of: Android has it from API 26.
internal actual fun probePath(path: Path): PathProbe = try {
    val attributes = Files.readAttributes(Paths.get(path.toString()), BasicFileAttributes::class.java)
    PathProbe.Present(
        FileMetadata(
            isRegularFile = attributes.isRegularFile,
            isDirectory = attributes.isDirectory,
            size = if (attributes.isRegularFile) attributes.size() else -1L,
        ),
    )
} catch (e: Exception) {
    e.asProbe()
}

internal actual fun listDenial(path: Path): PathProbe.Denied? = try {
    Files.newDirectoryStream(Paths.get(path.toString())).close()
    null
} catch (e: Exception) {
    // Anything but a denial means the directory is gone or unnameable, so the empty listing was honest.
    e.asProbe() as? PathProbe.Denied
}

/** What a failed lookup means: [PathProbe.Absent] when the path cannot resolve, [PathProbe.Denied] otherwise. */
internal fun Exception.asProbe(): PathProbe = when (this) {
    // Nothing is at a missing name, and nothing can be at one the platform refuses to parse at all.
    is NoSuchFileException, is InvalidPathException -> PathProbe.Absent
    is FileSystemException -> if (runsThroughFile()) PathProbe.Absent else PathProbe.Denied(this)
    else -> PathProbe.Denied(this)
}

// A path running through a file is absent, as ENOTDIR is natively. JDK 25 raises it as NoSuchFileException, but JDK
// 17 as a bare FileSystemException with only the system's message, so the nearest existing ancestor is asked instead.
private fun FileSystemException.runsThroughFile(): Boolean {
    var ancestor = file?.let { Paths.get(it).parent }
    while (ancestor != null) {
        if (Files.exists(ancestor)) return !Files.isDirectory(ancestor)
        ancestor = ancestor.parent
    }
    return false
}
