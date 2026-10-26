package com.fromwau.kern.fs

import kotlinx.io.files.FileMetadata
import kotlinx.io.files.Path

/** What a direct lookup found at a path. */
internal sealed interface PathProbe {
    data class Present(val metadata: FileMetadata) : PathProbe

    /** Nothing is there, and the lookup had the access it needed to prove it. */
    data object Absent : PathProbe

    /** The path could not be looked up at all, for what [failure] says. */
    data class Denied(val failure: Throwable) : PathProbe
}

/**
 * Looks [path] up in one call of its own, to tell a missing path from one that may not be looked up and to see what
 * is there at one moment, where kotlinx-io's answer does neither.
 */
internal expect fun probePath(path: Path): PathProbe

/**
 * Why listing [path] failed or came back empty, asked of the platform directly, or null when nothing refuses it.
 * kotlinx-io's JVM and Android backend lists a directory it may not read as an empty one, and its native one fails
 * with only a message.
 */
internal expect fun listDenial(path: Path): PathProbe.Denied?
