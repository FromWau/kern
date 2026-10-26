@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.linux.RLIMIT_FSIZE
import platform.linux.getrlimit
import platform.linux.rlimit
import platform.linux.setrlimit
import platform.linux.sockaddr_un
import platform.posix.AF_UNIX
import platform.posix.F_TLOCK
import platform.posix.O_CLOEXEC
import platform.posix.O_CREAT
import platform.posix.O_EXCL
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.PATH_MAX
import platform.posix.SIGKILL
import platform.posix.SIGXFSZ
import platform.posix.SOCK_STREAM
import platform.posix.STDERR_FILENO
import platform.posix.STDOUT_FILENO
import platform.posix.S_IFBLK
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.S_IRUSR
import platform.posix.S_IRWXG
import platform.posix.S_IRWXO
import platform.posix.S_IRWXU
import platform.posix.S_IWUSR
import platform.posix.S_IXUSR
import platform.posix.WNOHANG
import platform.posix._exit
import platform.posix.bind
import platform.posix.chdir
import platform.posix.chmod
import platform.posix.chown
import platform.posix.close
import platform.posix.closedir
import platform.posix.creat
import platform.posix.dup2
import platform.posix.execv
import platform.posix.fchmod
import platform.posix.fork
import platform.posix.getcwd
import platform.posix.getegid
import platform.posix.getgroups
import platform.posix.gid_tVar
import platform.posix.kill
import platform.posix.link
import platform.posix.lockf
import platform.posix.lstat
import platform.posix.open
import platform.posix.opendir
import platform.posix.pause
import platform.posix.readlink
import platform.posix.sa_family_tVar
import platform.posix.setsid
import platform.posix.signal
import platform.posix.socket
import platform.posix.stat
import platform.posix.symlink
import platform.posix.timeval
import platform.posix.umask
import platform.posix.unlink
import platform.posix.usleep
import platform.posix.utimes
import platform.posix.waitpid

@IgnorableReturnValue
internal fun Path.rawSymlinkTo(target: Path): Path = also { check(symlink(target.toString(), toString()) == 0) }

/** Makes this path a second name for [target]'s file. */
internal fun Path.rawHardLinkTo(target: Path): Path = also { check(link(target.toString(), toString()) == 0) }

@IgnorableReturnValue
internal fun Path.makeFifo(): Path = also { it.mkfifo().assertSuccess() }

/** Binds a unix domain socket at this path and closes it again, which leaves the socket file behind. */
@IgnorableReturnValue
internal fun Path.makeSocket(): Path = also { path ->
    val name = path.toString().encodeToByteArray()
    memScoped {
        val address = alloc<sockaddr_un>()
        // The name and the NUL after it have to fit, or bind would read a name cut short.
        check(name.size < sizeOf<sockaddr_un>() - sizeOf<sa_family_tVar>()) { "too long for a socket: $path" }
        address.sun_family = AF_UNIX.convert()
        name.forEachIndexed { index, byte -> address.sun_path[index] = byte }
        val descriptor = socket(AF_UNIX, SOCK_STREAM, 0)
        check(descriptor >= 0)
        try {
            check(bind(descriptor, address.ptr.reinterpret(), sizeOf<sockaddr_un>().convert()) == 0)
        } finally {
            close(descriptor)
        }
    }
}

/** The first block device in `/dev`, or null when this machine shows none, as in some containers. */
internal fun firstBlockDevice(): Path? = SystemFileSystem.list(Path("/dev")).firstOrNull { device ->
    memScoped {
        val info = alloc<stat>()
        stat(device.toString(), info.ptr) == 0 && info.st_mode.toInt() and S_IFMT == S_IFBLK
    }
}

/** Removes this symlink itself, which kotlinx-io's delete cannot do once the target is gone. */
internal fun Path.removeLink() {
    check(unlink(toString()) == 0) { "could not remove the link $this" }
}

internal fun Path.setMode(mode: Int) {
    check(chmod(toString(), mode.convert()) == 0) { "could not set the mode of $this" }
}

/** The permission bits alone, such as `S_IRUSR or S_IWUSR` for a `0600` file. */
internal fun Path.mode(): Int = checkNotNull(status { it.st_mode.toInt() and (S_IRWXU or S_IRWXG or S_IRWXO) })

internal fun Path.inode(): ULong = checkNotNull(status { it.st_ino })

/** The group that owns this file. */
internal fun Path.group(): UInt = checkNotNull(status { it.st_gid })

/** Hands this file to the group [gid], keeping its owner. */
internal fun Path.setGroup(gid: UInt) {
    check(chown(toString(), UInt.MAX_VALUE, gid) == 0)
}

/** A group this process's user belongs to besides its effective one, or null when it has only that one. */
internal fun otherGroupOrNull(): UInt? = memScoped {
    val count = getgroups(0, null)
    val groups = allocArray<gid_tVar>(count)
    val filled = getgroups(count, groups)
    (0 until filled)
        .map { groups[it] }
        .firstOrNull { it != getegid() }
}

internal fun Path.isLink(): Boolean = status(ofLink = true) { (it.st_mode.toInt() and S_IFMT) == S_IFLNK } ?: false

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

/**
 * Runs [block] with this directory at [mode] and readable again afterwards. [block] is skipped for a runner
 * that may read it anyway, such as root, which leaves nothing to observe.
 */
internal inline fun Path.withMode(
    mode: Int,
    block: () -> Unit,
) {
    setMode(mode)
    try {
        if (canList()) return
        block()
    } finally {
        setMode(S_IRWXU)
    }
}

/**
 * Runs [block] on what a write left in this directory, and makes it writable again afterwards: the write
 * locked it, and the cleanup after the test cannot remove anything while it stays that way.
 *
 * [block] is skipped for a runner that may write the directory anyway, such as root, whose undo succeeded
 * and left nothing to look at.
 */
internal inline fun Path.withModeRestored(block: () -> Unit) {
    try {
        if (canCreateFileIn()) return
        block()
    } finally {
        setMode(S_IRWXU)
    }
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
): T = withSizeLimit(bytes, staticCFunction<Int, Unit> { }, block)

// What [withFileSizeLimitLocking]'s signal handler needs: a descriptor opened up front and the mode to set.
// They are globals because a staticCFunction captures nothing, and a descriptor so the handler allocates none.
private var lockedPath = -1
private var lockedMode = S_IRUSR or S_IXUSR

/**
 * [withFileSizeLimit], with [path] set to [mode] the moment a write crosses the limit, so the failed write cannot
 * be undone either. A folder locked this way takes no new or removed entries, and a file no restored content. The
 * default suits a folder: it keeps the search bit, so paths inside it can still be looked up; drop it to make a
 * lookup fail rather than answer.
 */
internal fun <T> withFileSizeLimitLocking(
    path: Path,
    bytes: Long,
    mode: Int = S_IRUSR or S_IXUSR,
    block: () -> T,
): T {
    val descriptor = open(path.toString(), O_RDONLY)
    check(descriptor >= 0)
    lockedPath = descriptor
    lockedMode = mode
    try {
        return withSizeLimit(bytes, staticCFunction<Int, Unit> { fchmod(lockedPath, lockedMode.convert()) }, block)
    } finally {
        lockedPath = -1
        close(descriptor)
    }
}

/**
 * Runs [block] with the file size limit at [bytes] and [onCrossed] handling SIGXFSZ, putting both back afterwards.
 * Without a handler, crossing the limit ends the process with SIGXFSZ instead of failing the write.
 */
private fun <T> withSizeLimit(
    bytes: Long,
    onCrossed: CPointer<CFunction<(Int) -> Unit>>,
    block: () -> T,
): T {
    val previousHandler = signal(SIGXFSZ, onCrossed)
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

internal fun currentDirectory(): Path = memScoped {
    val buffer = allocArray<ByteVar>(PATH_MAX)
    check(getcwd(buffer, PATH_MAX.convert()) != null)
    Path(buffer.toKString())
}

internal fun changeDirectory(directory: Path) {
    check(chdir(directory.toString()) == 0)
}

/** Sets this file's access and modification time, to the microsecond, which is what `utimes` takes. */
internal fun Path.setModifiedTime(
    epochSeconds: Long,
    microseconds: Long,
) = memScoped {
    val times = allocArray<timeval>(2)
    for (index in 0..1) {
        times[index].tv_sec = epochSeconds
        times[index].tv_usec = microseconds
    }
    check(utimes(this@setModifiedTime.toString(), times) == 0)
}

/** The umask most systems start a user with: new files `0644`, new folders `0755`. */
internal const val UMASK_022 = 0b000_010_010

/** A umask that lets the group write: new files `0664`. */
internal const val UMASK_002 = 0b000_000_010

/** A umask that leaves new files and folders to their owner alone: new folders `0700`. */
internal const val UMASK_077 = 0b000_111_111

/** Runs [block] with the process's umask set to [mask], putting the one before back afterwards. */
internal fun <T> withUmask(
    mask: Int,
    block: () -> T,
): T {
    val previous = umask(mask.convert())
    try {
        return block()
    } finally {
        umask(previous)
    }
}

/**
 * Forks a child that takes the lock kern takes on [lockFile] and holds it until [stopLockHolder], and answers its pid
 * once it holds it, which it tells by making [marker]. The child makes system calls only, since a forked process
 * keeps just the thread that forked it.
 */
internal fun holdLockInChild(
    lockFile: Path,
    marker: Path,
): Int {
    val lockName = lockFile.toString()
    val markerName = marker.toString()
    val pid = fork()
    check(pid >= 0) { "fork failed" }
    if (pid == 0) {
        val descriptor = creat(lockName, 0b110_100_100.convert())
        if (descriptor < 0 || lockf(descriptor, F_TLOCK, 0) != 0) _exit(1)
        close(creat(markerName, 0b110_100_100.convert()))
        while (true) pause()
    }
    repeat(LOCK_HOLDER_START_POLLS) {
        if (SystemFileSystem.exists(marker)) return pid
        check(waitpid(pid, null, WNOHANG) == 0) { "the lock holder died" }
        usleep(LOCK_HOLDER_POLL_MICROS.convert())
    }
    stopLockHolder(pid)
    error("the lock holder never reported holding the lock")
}

/** Ends the child [holdLockInChild] started, which lets go of its lock. */
internal fun stopLockHolder(pid: Int) {
    kill(pid, SIGKILL)
    waitpid(pid, null, 0)
}

private const val LOCK_HOLDER_START_POLLS = 1500

private const val LOCK_HOLDER_POLL_MICROS = 20_000

/** Whether this process has a controlling terminal, which `/dev/tty` opens to. */
internal fun hasControllingTerminal(): Boolean {
    val descriptor = open("/dev/tty", O_RDONLY or O_NONBLOCK or O_CLOEXEC)
    if (descriptor < 0) return false
    close(descriptor)
    return true
}

/**
 * Runs the test named [test], as `package.Class.name`, in a child of this test binary that starts a session of its own
 * and so has no controlling terminal, writing its output to [log]. Answers its wait status, 0 once it passed, or null
 * when it ran too long. The child makes system calls only before it execs, since a forked process keeps just the
 * thread that forked it.
 */
internal fun runTestInNewSession(
    test: String,
    log: Path,
): Int? = memScoped {
    val self = allocArray<ByteVar>(PATH_MAX)
    val length = readlink("/proc/self/exe", self, (PATH_MAX - 1).convert())
    check(length > 0) { "no path to this test binary" }
    self[length] = 0
    val selfPath = self.toKString()
    val arguments = allocArrayOf(self, "--ktest_filter=$test".cstr.ptr, null)
    val output = open(log.toString(), O_WRONLY or O_CREAT or O_TRUNC or O_CLOEXEC, 0b110_000_000)
    check(output >= 0) { "cannot open $log" }
    val pid = fork()
    check(pid >= 0) { "fork failed" }
    if (pid == 0) {
        setsid()
        dup2(output, STDOUT_FILENO)
        dup2(output, STDERR_FILENO)
        execv(selfPath, arguments)
        _exit(127)
    }
    close(output)
    val status = alloc<IntVar>()
    repeat(CHILD_TEST_POLLS) {
        if (waitpid(pid, status.ptr, WNOHANG) == pid) return status.value
        usleep(CHILD_TEST_POLL_MICROS.convert())
    }
    kill(pid, SIGKILL)
    waitpid(pid, null, 0)
    null
}

private const val CHILD_TEST_POLLS = 1500

private const val CHILD_TEST_POLL_MICROS = 20_000
