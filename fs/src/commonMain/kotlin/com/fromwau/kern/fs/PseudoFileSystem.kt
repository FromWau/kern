package com.fromwau.kern.fs

/** A Linux file system whose files the kernel makes up as they are read, as [FileError.Unwatchable] names it. */
public enum class PseudoFileSystem {
    /** procfs, mounted at `/proc`: `/proc/meminfo`, `/proc/stat`, `/proc/self/status`. */
    Proc,

    /** sysfs, mounted at `/sys`: `/sys/class/hwmon/hwmon0/temp1_input`, `/sys/kernel/uevent_seqnum`. */
    Sys,
}
