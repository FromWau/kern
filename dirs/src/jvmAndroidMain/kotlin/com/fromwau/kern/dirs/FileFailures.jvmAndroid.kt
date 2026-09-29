package com.fromwau.kern.dirs

import kotlinx.io.files.Path
import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Paths

// kotlinx-io removes through java.io.File here, which answers false and nothing more, so java.nio is asked
// instead: its exceptions say why, and a removal that works this time is simply done.
internal actual fun whyNotDeleted(path: Path, failure: Exception): String? = try {
    Files.delete(Paths.get(path.toString()))
    null
} catch (_: DirectoryNotEmptyException) {
    // The message is only the path, which the error already carries, so it says what the native targets say.
    "Directory not empty"
} catch (_: AccessDeniedException) {
    "Permission denied"
} catch (e: FileSystemException) {
    e.reason ?: e::class.java.simpleName
} catch (_: Exception) {
    failure.reason
}

// kotlinx-io creates through java.io.File.mkdirs here, which also answers false and nothing more.
internal actual fun whyNotCreated(path: Path): String? = try {
    Files.createDirectory(Paths.get(path.toString()))
    null
} catch (_: FileAlreadyExistsException) {
    null
} catch (_: AccessDeniedException) {
    "Permission denied"
} catch (e: FileSystemException) {
    e.reason ?: e::class.java.simpleName
} catch (e: Exception) {
    e.reason
}
