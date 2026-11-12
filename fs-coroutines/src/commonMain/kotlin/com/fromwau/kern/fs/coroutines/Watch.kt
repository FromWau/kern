package com.fromwau.kern.fs.coroutines

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How [textChanges] notices that a file's text may have changed: any [Watch], or [Read] for a file whose text moves
 * while its metadata stays still.
 */
public sealed interface TextWatch {
    /**
     * Read the file every [every], whatever its metadata says. Use it for procfs and sysfs files, such as
     * `/proc/stat`, `/proc/meminfo` or a hwmon `temp1_input`, whose size, modification time and change time stay as
     * they were while the text moves, so no [Watch] could follow them: a [Watch] there answers
     * [FileError.Unwatchable][com.fromwau.kern.fs.FileError.Unwatchable]. For an ordinary file use a [Watch], which
     * reads only when the file changed.
     *
     * @throws IllegalArgumentException when [every] is not positive.
     */
    public data class Read(val every: Duration) : TextWatch {
        init {
            require(every.isPositive()) { "every must be positive, was $every" }
        }
    }
}

/** How [changes] and [textChanges] notice that a file changed: by its [info][com.fromwau.kern.fs.info]. */
public sealed interface Watch : TextWatch {
    /**
     * The operating system says when the file changed, and the file is looked up every [fallback] where it will not:
     * after the system refused to watch or a root such as a drive was missing, and on the JVM on macOS. The one to use
     * unless it misses changes.
     *
     * @throws IllegalArgumentException when [fallback] is not positive.
     */
    public data class System(val fallback: Duration = 1.seconds) : Watch {
        init {
            require(fallback.isPositive()) { "fallback must be positive, was $fallback" }
        }
    }

    /**
     * The file is looked up every [every], never asking the operating system to watch. Use it where that watch misses
     * changes: on NFS and SMB, inotify does not report a change made on another machine, and on a FUSE mount, a change
     * the file system makes behind the mount. A change undone before the next lookup gives no answer.
     *
     * @throws IllegalArgumentException when [every] is not positive.
     */
    public data class Poll(val every: Duration = 1.seconds) : Watch {
        init {
            require(every.isPositive()) { "every must be positive, was $every" }
        }
    }
}
