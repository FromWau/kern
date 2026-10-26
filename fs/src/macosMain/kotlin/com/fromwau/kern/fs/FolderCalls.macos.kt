package com.fromwau.kern.fs

import platform.posix.open

// macOS's Kotlin/Native headers leave out openat, so a name is opened only from the working directory here, and a
// writer reaches each folder by its path, as on the JVM.
internal actual fun openAt(
    folder: Int,
    name: String,
    flags: Int,
    mode: Int,
): Int {
    check(folder == WORKING_DIRECTORY) { "no openat on macOS" }
    return open(name, flags, mode)
}
