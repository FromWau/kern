package com.fromwau.kern.fs

import java.io.File
import java.io.RandomAccessFile
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
internal actual fun tryOsLock(lockFile: Path): OsLock {
    val opened = try {
        RandomAccessFile(File(lockFile.toString()), "rw")
    } catch (e: Exception) {
        return OsLock.Failed(e.reason)
    }
    val outcome = try {
        // A null tryLock means another process holds it. java.nio exposes no F_GETLK, so it cannot be named.
        if (opened.channel.tryLock() != null) null else OsLock.Busy(holderPid = null)
    } catch (e: Exception) {
        OsLock.Failed(e.reason)
    }
    if (outcome != null) {
        try {
            opened.close()
        } catch (_: Exception) {
            // No lock was taken, so a close that fails leaves nothing held.
        }
        return outcome
    }
    return OsLock.Held { opened.close() }
}

// Thread.sleep clears the interrupt as it throws, so it is set again for whoever asked this thread to stop.
internal actual fun sleepMillis(millis: Long): Boolean = try {
    Thread.sleep(millis)
    true
} catch (_: InterruptedException) {
    Thread.currentThread().interrupt()
    false
}
