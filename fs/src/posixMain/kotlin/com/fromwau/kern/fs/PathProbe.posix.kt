package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.io.files.Path
import platform.posix.closedir
import platform.posix.opendir

// kotlinx-io's listing failure carries only a message, so the directory is opened here to read the errno.
@OptIn(ExperimentalForeignApi::class)
internal actual fun listDenial(path: Path): PathProbe.Denied? {
    val directory = opendir(path.toString())
    if (directory != null) {
        closedir(directory)
        return null
    }
    // Gone, or no longer a folder: nothing refused the listing, so its own failure stands.
    return deniedUnlessGone(path)
}
