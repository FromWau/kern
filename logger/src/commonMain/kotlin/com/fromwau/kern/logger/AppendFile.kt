package com.fromwau.kern.logger

import kotlinx.io.files.Path

/**
 * A log file opened for appending that takes each entry in one write, so another process's or [Logger]'s entry
 * lands before or after it, never inside. A buffered sink would split a long entry into several writes.
 */
internal interface AppendFile {
    /** Appends [bytes] in one write. Throws when the system refuses it. */
    fun append(bytes: ByteArray)

    fun close()
}

/** Opens [path] for appending, creating the file when it is missing. Throws when the system refuses it. */
internal expect fun openAppendFile(path: Path): AppendFile
