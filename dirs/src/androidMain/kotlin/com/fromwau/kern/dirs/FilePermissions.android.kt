package com.fromwau.kern.dirs

import android.system.Os
import kotlinx.io.files.Path

// Android asks the system calls itself rather than sharing the JVM's implementation: `Os.chmod` is there from
// API 21 and does one thing, while whether Android's own `java.nio.file` hands out a posix attribute view is
// not something this project can run a test for. A view it declined would leave this doing nothing, quietly.
internal actual fun copyPermissions(
    from: Path,
    to: Path,
) {
    Os.chmod(to.toString(), Os.stat(from.toString()).st_mode and PERMISSION_BITS)
}
