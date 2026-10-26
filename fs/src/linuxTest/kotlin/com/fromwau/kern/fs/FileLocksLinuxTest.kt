@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import platform.posix.FD_CLOEXEC
import platform.posix.F_GETFD
import platform.posix.PATH_MAX
import platform.posix.fcntl
import platform.posix.getpid
import platform.posix.readlink

class FileLocksLinuxTest {
    private val dir = newTempDir()
    private val lockFile = dir / ".app.lock"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a new lock file is rw-r--r-- under a umask that lets the group write`() {
        withUmask(UMASK_002) { lockFile.tryLock().assertSuccess().close() }

        assertEquals(0b110_100_100, lockFile.mode())
    }

    @Test
    fun `a holder in this process is named by this process's pid`() {
        lockFile.tryLock().assertSuccess().use {
            val busy = lockFile.tryLock().assertError<FileError.LockBusy>()

            assertEquals(getpid().toLong(), busy.holderPid)
        }
    }

    @Test
    fun `a FIFO at the lock path is NotRegularFile`() {
        val fifo = (dir / "pipe.lock").makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.withLock { })
        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.tryLock())
    }

    @Test
    fun `a lock's descriptor is closed on exec`() {
        lockFile.tryLock().assertSuccess().use {
            val flags = descriptorsOn(lockFile.canonicalPath().assertSuccess()).map { fcntl(it, F_GETFD) }

            assertEquals(listOf(FD_CLOEXEC), flags.map { it and FD_CLOEXEC })
        }
    }

    /** The descriptors this process has open on [file], as `/proc/self/fd` lists them. */
    private fun descriptorsOn(file: Path): List<Int> = (0 until 1024).filter { descriptor ->
        memScoped {
            val target = allocArray<ByteVar>(PATH_MAX)
            val length = readlink("/proc/self/fd/$descriptor", target, PATH_MAX.convert())
            length > 0 && target.readBytes(length.toInt()).decodeToString() == file.toString()
        }
    }
}
