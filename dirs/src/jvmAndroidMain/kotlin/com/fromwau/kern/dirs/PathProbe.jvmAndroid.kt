package com.fromwau.kern.dirs

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path
import java.nio.file.AccessDeniedException
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
private fun Exception.asProbe(): PathProbe = when (this) {
    // Nothing is at a missing name, and nothing can be at one the platform refuses to parse at all.
    is NoSuchFileException, is InvalidPathException -> PathProbe.Absent
    // The message is only the path, which the error already carries, so it says what the native targets say.
    is AccessDeniedException -> PathProbe.Denied("Permission denied")
    is FileSystemException -> PathProbe.Denied(fault)
    else -> PathProbe.Denied(reason)
}

// getMessage() leads with the path too, and for a loop or an over-long name that is the whole of it. `reason`
// here is the exception's own member, not kern's extension.
private val FileSystemException.fault: String
    get() = reason ?: this::class.java.simpleName
