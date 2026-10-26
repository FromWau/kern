package com.fromwau.kern.fs

import android.system.ErrnoException
import android.system.Os
import kotlinx.io.files.Path

// The system calls themselves, as the permission calls do here: Android's java.nio is not something this project
// can run a test for.
internal actual fun fileIdentity(path: Path): FileIdentity? = try {
    val status = Os.stat(path.toString())
    FileIdentity(
        linkCount = status.st_nlink,
        ownedByCurrentUser = status.st_uid == Os.geteuid(),
    )
} catch (_: ErrnoException) {
    null
}
