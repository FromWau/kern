package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.FileSystemLoopException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import java.nio.file.ReadOnlyFileSystemException

// kotlinx-io removes through java.io.File here, which answers false and nothing more, so java.nio is asked
// instead: its exceptions say why by their type, and a removal that works this time is simply done.
internal actual fun whyNotDeleted(path: Path, failure: Exception): Exception? = try {
    Files.delete(Paths.get(path.toString()))
    null
} catch (e: FileSystemException) {
    e
} catch (_: Exception) {
    failure
}

// kotlinx-io wraps an exception that is no IOException, such as ReadOnlyFileSystemException, in one of its own.
internal actual fun Throwable.platformCause(): IoCause = typedCause() ?: cause?.typedCause() ?: IoCause.Other

private fun Throwable.typedCause(): IoCause? = when (this) {
    is AccessDeniedException -> IoCause.Denied
    is NoSuchFileException -> IoCause.NotFound
    is FileSystemLoopException -> IoCause.Loop
    is ReadOnlyFileSystemException -> IoCause.ReadOnly
    else -> null
}
