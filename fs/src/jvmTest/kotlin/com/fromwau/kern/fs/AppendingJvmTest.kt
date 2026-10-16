package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

class AppendingJvmTest {
    private val dir = newTempDir()
    private val file = dir / "app.log"
    private val rotated = dir / "app.log.1"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a file whose other hard link remains is moved rather than deleted`() {
        assumeTrue(hasProc(), "no /proc to read the open file's link count through")
        val opened = file.openAppend().assertSuccess()
        val _ = rotated.hardLinkTo(file)

        Files.delete(Paths.get(file.toString()))

        assertEquals(AppendState.Moved, opened.state())
        opened.close()
    }

    @Test
    fun `a moved file is Moved where proc shows the open file and Unknown elsewhere`() {
        val opened = file.openAppend().assertSuccess()
        val before = opened.state()

        moveOpenFile()
        val after = opened.state()
        opened.close()

        assertEquals(AppendState.Same, before)
        assertEquals(if (hasProc()) AppendState.Moved else AppendState.Unknown, after)
    }

    @Test
    fun `a new file at the path does not make a moved one Same`() {
        val opened = file.openAppend().assertSuccess()

        moveOpenFile()
        File(file.toString()).writeText("")
        val state = opened.state()
        opened.close()

        assertEquals(if (hasProc()) AppendState.Moved else AppendState.Unknown, state)
    }

    @Test
    fun `a deleted file is Deleted where proc shows the open file and Unknown elsewhere`() {
        val opened = file.openAppend().assertSuccess()

        Files.delete(Paths.get(file.toString()))

        assertEquals(if (hasProc()) AppendState.Deleted else AppendState.Unknown, opened.state())
        opened.close()
    }

    @Test
    fun `the last byte of a deleted file is read from the open file`() {
        assumeTrue(hasProc(), "no /proc to reach the open file through")
        val opened = file.openAppend().assertSuccess()
        assertEquals(Ok(Unit), opened.append("line\n".encodeToByteArray()))

        Files.delete(Paths.get(file.toString()))
        val last = opened.lastByte()
        opened.close()

        assertEquals(Ok('\n'.code.toByte()), last)
    }

    @Test
    fun `a file rotated while it is being opened is not the one kept open`() {
        File(file.toString()).writeText("old\n")
        var rotate = true
        val opened = openAppendFile(file) {
            if (rotate) {
                rotate = false
                Files.move(Paths.get(file.toString()), Paths.get(rotated.toString()), StandardCopyOption.ATOMIC_MOVE)
                File(file.toString()).writeText("")
            }
        }.assertSuccess()

        assertEquals(Ok(Unit), opened.append("new\n".encodeToByteArray()))
        opened.close()

        assertEquals("new\n", File(file.toString()).readText())
        assertEquals("old\n", File(rotated.toString()).readText())
    }

    @Test
    fun `a file deleted while it is being opened leaks no handle`() {
        assumeTrue(hasProc(), "no /proc/self/fd to count open files with")
        val descriptors = File("/proc/self/fd")
        File(file.toString()).writeText("")
        val before = descriptors.list()!!.size
        var delete = true

        val opened = openAppendFile(file) {
            if (delete) {
                delete = false
                Files.delete(Paths.get(file.toString()))
            }
        }.assertSuccess()
        assertEquals(Ok(Unit), opened.append("after\n".encodeToByteArray()))
        opened.close()

        assertEquals(before, descriptors.list()!!.size, "open file descriptors")
        assertEquals("after\n", File(file.toString()).readText())
    }

    @Test
    fun `the last byte of a huge file is read without reading the rest`() {
        // Sparse, so it costs no disk, but reading it through would take far longer than the limit below.
        RandomAccessFile(file.toString(), "rw").use { it.setLength(256L shl 30) }
        val opened = file.openAppend().assertSuccess()

        val (last, took) = measureTimedValue { opened.lastByte() }
        opened.close()

        assertEquals(Ok(0.toByte()), last)
        assertTrue(took < 1.seconds, "reading the last byte took $took")
    }

    @Test
    fun `a file this process may only write is appended to and its last byte is Inaccessible`() {
        File(file.toString()).writeText("torn")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("-w-------"))
        assumeTrue(!Files.isReadable(Paths.get(file.toString())), "this user reads files without read permission")
        val opened = file.openAppend().assertSuccess()

        val appended = opened.append("\n".encodeToByteArray())
        val last = opened.lastByte()
        opened.close()

        assertEquals(Ok(Unit), appended)
        last.assertError<FileError.Inaccessible>()
    }

    @Test
    fun `a device is not a file to append to`() {
        assumeTrue(File("/dev/null").exists(), "no /dev/null")

        val error = Path("/dev/null").openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.Other, error.type)
    }

    /** Moves [file] to [rotated] while it is open, skipping the test where the platform refuses, as Windows does. */
    private fun moveOpenFile() {
        val moved = try {
            Files.move(Paths.get(file.toString()), Paths.get(rotated.toString()), StandardCopyOption.ATOMIC_MOVE)
            true
        } catch (_: java.io.IOException) {
            false
        }
        assumeTrue(moved, "an open file cannot be moved on this platform")
    }

    private fun hasProc(): Boolean = File("/proc/self/fd").isDirectory
}
