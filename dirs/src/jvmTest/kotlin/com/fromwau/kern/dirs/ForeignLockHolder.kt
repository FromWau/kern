package com.fromwau.kern.dirs

import java.io.File
import java.io.RandomAccessFile

/**
 * Takes a [withLock] lock from a separate process and holds it.
 *
 * It has to be a real process: a second thread here would stop at this process's own gate and never exercise
 * the kernel wait this exists to bound. Spawned by [FileLocksJvmTest].
 */
object ForeignLockHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val (lockPath, markerPath, holdMillis) = args
        RandomAccessFile(lockPath, "rw").use { handle ->
            handle.channel.lock().use {
                File(markerPath).writeText("held") // tells the test the lock is genuinely taken
                Thread.sleep(holdMillis.toLong())
            }
        }
    }
}
