package com.fromwau.kern.fs

import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import kotlinx.io.files.Path

/**
 * `FileChannel` locks are fcntl-based on Linux, the same primitive the posix actual uses, so a JVM holder and
 * a native one contend with each other rather than passing straight through.
 *
 * `tryLock`, one try, not the blocking `lock()`: [withLock] promises a bounded wait, and `lock()` waits for a
 * holder forever. A guarantee that holds on only one runtime is worse than none.
 *
 * The descriptor is opened and closed inside the caller's in-process gate, which is load-bearing rather than
 * tidy. Closing any descriptor to a file drops every lock this process holds on it, so a descriptor opened
 * before the gate and closed after it would destroy another thread's lock while that thread was still inside
 * its critical section, and silently, since `FileLock.isValid` stays true.
 */
internal actual fun tryOsLock(
    lockFile: Path,
    permissions: Int?,
    newMode: Int,
): OsLock {
    val opened = try {
        // NIO, not RandomAccessFile, opens with FILE_SHARE_DELETE on Windows, and the lock file is deleted while held.
        FileChannel.open(
            Paths.get(lockFile.toString()),
            setOf(StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE),
            *creationAttributes(permissions ?: newMode),
        )
    } catch (e: Exception) {
        return OsLock.NotOpened(e)
    }
    try {
        // Set as well as created with, since the umask can narrow a new file's mode and one already there has its own.
        if (permissions != null) setFilePermissions(lockFile, permissions)
    } catch (e: Exception) {
        opened.close()
        return OsLock.NotOpened(e)
    }
    val outcome = try {
        // A null tryLock means another process holds it. java.nio exposes no F_GETLK, so it cannot be named.
        if (opened.tryLock() != null) null else OsLock.Busy(holderPid = null)
    } catch (_: OverlappingFileLockException) {
        // This JVM already holds it, under a spelling of the file that passed the in-process gate.
        OsLock.Busy(holderPid = currentProcessId())
    } catch (e: Exception) {
        OsLock.Failed(e)
    }
    if (outcome != null) {
        try {
            opened.close()
        } catch (_: Exception) {
            // No lock was taken, so a close that fails leaves nothing held.
        }
        return outcome
    }
    // Java cannot ask a channel for its file key, so the path's is read once the lock is held.
    return OsLock.Held(fileKeyOrNull(lockFile)) { opened.close() }
}

internal actual fun fileKeyOrNull(path: Path): Any? = try {
    keyAt(path).takeUnless { it == NoFile }
} catch (_: Exception) {
    null
}

// Thread.sleep clears the interrupt as it throws, so it is set again for whoever asked this thread to stop.
internal actual fun sleepMillis(millis: Long): Boolean = try {
    Thread.sleep(millis)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
