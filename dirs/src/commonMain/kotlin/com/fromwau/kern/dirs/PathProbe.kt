package com.fromwau.kern.dirs

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path

/** What a direct lookup found at a path. */
internal sealed interface PathProbe {
    data class Present(val metadata: FileMetadata) : PathProbe

    /** Nothing is there, and the lookup had the access it needed to prove it. */
    data object Absent : PathProbe

    /** The path could not be looked up at all. [reason] is the OS message. */
    data class Denied(val reason: String) : PathProbe
}

/**
 * Looks [path] up without going through kotlinx-io, whose JVM, Android and Apple backends report a refused
 * lookup as a missing file.
 */
internal expect fun probePath(path: Path): PathProbe

/**
 * Why listing [path] came back empty, or null when it really holds nothing. kotlinx-io's JVM and Android backend
 * lists a directory it may not read as an empty one.
 */
internal expect fun listDenial(path: Path): PathProbe.Denied?
