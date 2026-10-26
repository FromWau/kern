package com.fromwau.kern.fs

import kotlinx.io.files.Path
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.nio.file.StandardCopyOption

// A name java.nio cannot parse, as Windows refuses one holding `<` or `?`, can be no link.
internal actual fun isSymlink(path: Path): Boolean = try {
    Files.isSymbolicLink(Paths.get(path.toString()))
} catch (_: InvalidPathException) {
    false
}

internal actual fun readLink(path: Path): Path = Path(Files.readSymbolicLink(Paths.get(path.toString())).toString())

internal actual fun deleteLink(path: Path): Exception? = try {
    Files.delete(Paths.get(path.toString()))
    null
} catch (e: Exception) {
    e
}

internal actual fun moveLink(
    path: Path,
    target: Path,
): Exception? = try {
    Files.move(
        Paths.get(path.toString()),
        Paths.get(target.toString()),
        LinkOption.NOFOLLOW_LINKS,
        StandardCopyOption.ATOMIC_MOVE,
    )
    null
} catch (e: Exception) {
    e
}

internal actual fun createLink(
    path: Path,
    target: Path,
): Exception? = try {
    Files.createSymbolicLink(Paths.get(path.toString()), Paths.get(target.toString()))
    null
} catch (e: Exception) {
    e
}

// java.nio follows links on every platform, Windows included, where kotlinx-io's resolve may not.
internal actual fun realPath(path: Path): Path = Path(
    Paths
        .get(path.toString())
        .toRealPath()
        .toString(),
)
