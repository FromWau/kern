package com.fromwau.kern.fs

import platform.posix.EPERM
import platform.posix.errno
import platform.posix.getpid
import platform.posix.kill

public actual fun currentProcessId(): Long = getpid().toLong()

// Signal 0 checks without sending; EPERM means a process is there that this user may not signal.
internal actual fun isProcessRunning(pid: Long): Boolean =
    pid <= Int.MAX_VALUE && (kill(pid.toInt(), 0) == 0 || errno == EPERM)
