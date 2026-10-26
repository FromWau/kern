package com.fromwau.kern.fs

import android.os.Process
import java.io.File

public actual fun currentProcessId(): Long = Process.myPid().toLong()

// Android has no ProcessHandle, and every live process has its folder under /proc.
internal actual fun isProcessRunning(pid: Long): Boolean = File("/proc/$pid").exists()
