package com.fromwau.kern.fs

public actual fun currentProcessId(): Long = ProcessHandle.current().pid()

internal actual fun isProcessRunning(pid: Long): Boolean = ProcessHandle.of(pid).isPresent
