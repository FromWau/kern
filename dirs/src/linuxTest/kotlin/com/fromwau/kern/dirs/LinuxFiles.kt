@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.dirs

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.io.files.Path
import platform.linux.RLIMIT_FSIZE
import platform.linux.getrlimit
import platform.linux.rlimit
import platform.linux.setrlimit
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.SIGXFSZ
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IRUSR
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.chdir
import platform.posix.chmod
import platform.posix.close
import platform.posix.closedir
import platform.posix.fchmod
import platform.posix.getcwd
import platform.posix.lstat
import platform.posix.mkfifo
import platform.posix.open
import platform.posix.opendir
import platform.posix.read
import platform.posix.signal
import platform.posix.stat
import platform.posix.symlink
import platform.posix.unlink

internal fun Path.symlinkTo(target: Path): Path = also { check(symlink(target.toString(), toString()) == 0) }

internal fun Path.makeFifo(): Path = also { check(mkfifo(toString(), (S_IRUSR or S_IWUSR).convert()) == 0) }

/** Removes this symlink itself, which kotlinx-io's delete cannot do once the target is gone. */
internal fun Path.removeLink() {
    unlink(toString())
}

internal fun Path.setMode(mode: Int) {
    chmod(toString(), mode.convert())
}

/** The permission bits alone, such as `S_IRUSR or S_IWUSR` for a `0600` file. */
internal fun Path.mode(): Int = checkNotNull(status { it.st_mode.toInt() and (S_IRWXU or S_IRWXG or S_IRWXO) })

internal fun Path.inode(): ULong = checkNotNull(status { it.st_ino })

internal fun Path.isSymlink(): Boolean = status(ofLink = true) { (it.st_mode.toInt() and S_IFMT) == S_IFLNK } ?: false

/** [read] applied to this path's `stat`, or its `lstat` when [ofLink] is true; null when that call fails. */
private inline fun <T> Path.status(
    ofLink: Boolean = false,
    read: (stat) -> T,
): T? {
    // Read outside memScoped, where toString() would answer for the MemScope receiver instead.
    val path = toString()
    return memScoped {
        val info = alloc<stat>()
        val result = if (ofLink) lstat(path, info.ptr) else stat(path, info.ptr)
        if (result == 0) read(info) else null
    }
}

// The permission checks below try the operation itself: access() checks a non-root runner without the
// capabilities, such as CAP_DAC_OVERRIDE in a container, that let the real operation succeed.

/** Whether this file can be opened for writing. The open does not empty it. */
internal fun Path.canOpenForWriting(): Boolean = canOpen(O_WRONLY)

internal fun Path.canOpenForReading(): Boolean = canOpen(O_RDONLY)

private fun Path.canOpen(flags: Int): Boolean {
    val descriptor = open(toString(), flags)
    if (descriptor < 0) return false
    close(descriptor)
    return true
}

/** Whether a file can be created in this folder. The probe file is removed again. */
internal fun Path.canCreateFileIn(): Boolean {
    val probe = Path(this, ".probe").toString()
    val descriptor = open(probe, O_WRONLY or O_CREAT or O_EXCL, S_IRUSR or S_IWUSR)
    if (descriptor < 0) return false
    close(descriptor)
    unlink(probe)
    return true
}

internal fun Path.canList(): Boolean {
    val directory = opendir(toString()) ?: return false
    closedir(directory)
    return true
}

/** Runs [block] with every write past [bytes] into one file failing with EFBIG, as a full disk fails it. */
internal fun <T> withFileSizeLimit(
    bytes: Long,
    block: () -> T,
): T {
    // Without a handler, crossing the limit ends the process with SIGXFSZ instead of failing the write.
    val previousHandler = signal(SIGXFSZ, staticCFunction<Int, Unit> { })
    return memScoped {
        val previous = alloc<rlimit>()
        check(getrlimit(RLIMIT_FSIZE, previous.ptr) == 0)
        val limited = alloc<rlimit>()
        limited.rlim_cur = bytes.convert()
        limited.rlim_max = previous.rlim_max
        check(setrlimit(RLIMIT_FSIZE, limited.ptr) == 0)
        try {
            block()
        } finally {
            setrlimit(RLIMIT_FSIZE, previous.ptr)
            signal(SIGXFSZ, previousHandler)
        }
    }
}

/** The directory [withFileSizeLimitLocking] locks, as a descriptor so its signal handler allocates nothing. */
private var lockedDirectory = -1

/**
 * [withFileSizeLimit], with [directory] turned read-only the moment a write crosses the limit, so the failed
 * write cannot rename its backup back either.
 */
internal fun <T> withFileSizeLimitLocking(
    directory: Path,
    bytes: Long,
    block: () -> T,
): T {
    val descriptor = open(directory.toString(), O_RDONLY)
    check(descriptor >= 0)
    lockedDirectory = descriptor
    // The handler runs inside the interrupted write, so it only calls fchmod on a descriptor opened up front.
    val previousHandler = signal(
        SIGXFSZ,
        staticCFunction<Int, Unit> { fchmod(lockedDirectory, (S_IRUSR or S_IXUSR).convert()) },
    )
    return memScoped {
        val previous = alloc<rlimit>()
        check(getrlimit(RLIMIT_FSIZE, previous.ptr) == 0)
        val limited = alloc<rlimit>()
        limited.rlim_cur = bytes.convert()
        limited.rlim_max = previous.rlim_max
        check(setrlimit(RLIMIT_FSIZE, limited.ptr) == 0)
        try {
            block()
        } finally {
            setrlimit(RLIMIT_FSIZE, previous.ptr)
            signal(SIGXFSZ, previousHandler)
            lockedDirectory = -1
            close(descriptor)
        }
    }
}

/** Reads a file to its end through raw descriptors, so a Worker can drain a FIFO without the code under test. */
internal fun readFully(path: String): String = memScoped {
    val descriptor = open(path, O_RDONLY)
    check(descriptor >= 0)
    val capacity = 4096
    val buffer = allocArray<ByteVar>(capacity)
    val content = StringBuilder()
    while (true) {
        val count = read(descriptor, buffer, capacity.convert()).toInt()
        if (count <= 0) break
        content.append(buffer.readBytes(count).decodeToString())
    }
    close(descriptor)
    content.toString()
}

internal fun currentDirectory(): Path = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    check(getcwd(buffer, PATH_MAX.convert()) != null)
    Path(buffer.toKString())
}

internal fun changeDirectory(directory: Path) {
    check(chdir(directory.toString()) == 0)
}
