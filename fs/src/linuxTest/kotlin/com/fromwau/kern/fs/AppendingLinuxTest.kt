package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.S_IWUSR
import platform.posix.truncate

class AppendingLinuxTest {
    private val dir = newTempDir()
    private val file = dir / "app.log"

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    @Test
    fun `a moved file is Moved`() {
        val opened = file.openAppend().assertSuccess()
        val before = opened.state()

        SystemFileSystem.atomicMove(file, dir / "app.log.1")
        val after = opened.state()
        opened.close()

        assertEquals(AppendState.Same, before)
        assertEquals(AppendState.Moved, after)
    }

    @Test
    fun `a new file at the path does not make a moved one Same`() {
        val opened = file.openAppend().assertSuccess()

        SystemFileSystem.atomicMove(file, dir / "app.log.1")
        file.writeRaw("")
        val state = opened.state()
        opened.close()

        assertEquals(AppendState.Moved, state)
    }

    @Test
    fun `a deleted file is Deleted`() {
        val opened = file.openAppend().assertSuccess()

        SystemFileSystem.delete(file)
        val state = opened.state()
        opened.close()

        assertEquals(AppendState.Deleted, state)
    }

    @Test
    fun `a file whose other hard link remains is moved rather than deleted`() {
        val opened = file.openAppend().assertSuccess()
        val _ = (dir / "app.log.1").hardLinkTo(file)

        SystemFileSystem.delete(file)
        val state = opened.state()
        opened.close()

        assertEquals(AppendState.Moved, state)
    }

    @Test
    fun `the last byte of a deleted file is read from the open file`() {
        val opened = file.openAppend().assertSuccess()
        assertEquals(Ok(Unit), opened.append("line\n".encodeToByteArray()))

        SystemFileSystem.delete(file)
        val last = opened.lastByte()
        opened.close()

        assertEquals(Ok('\n'.code.toByte()), last)
    }

    @Test
    fun `a FIFO at the path is NotRegularFile and the open does not wait for a reader`() {
        val fifo = (dir / "pipe").makeFifo()

        val error = fifo.openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.Other, error.type)
    }

    @Test
    fun `a device at the path is NotRegularFile`() {
        val error = Path("/dev/full").openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.Other, error.type)
    }

    @Test
    fun `a write the system refuses is WriteFailed with its reason`() {
        val opened = file.openAppend().assertSuccess()

        val refused = withFileSizeLimit(bytes = 4) { opened.append("past the limit\n".encodeToByteArray()) }
        opened.close()

        val error = refused.assertError<FileError.WriteFailed>()
        assertEquals("File too large", error.reason)
    }

    @Test
    fun `a file this process may only write is appended to and its last byte is Inaccessible`() {
        file.writeRaw("torn")
        file.setMode(S_IWUSR)
        if (file.canOpenForReading()) return
        val opened = file.openAppend().assertSuccess()

        val appended = opened.append("\n".encodeToByteArray())
        val last = opened.lastByte()
        opened.close()

        assertEquals(Ok(Unit), appended)
        last.assertError<FileError.Inaccessible>()
    }

    @Test
    fun `the last byte of a huge file is read without reading the rest`() {
        file.writeRaw("")
        // Sparse, so it costs no disk, but reading it through would take far longer than the limit below.
        assertEquals(0, truncate(file.toString(), 256L shl 30))
        val opened = file.openAppend().assertSuccess()

        val (last, took) = TimeSource.Monotonic.measureTimedValue { opened.lastByte() }
        opened.close()

        assertEquals(Ok(0.toByte()), last)
        assertTrue(took < 1.seconds, "reading the last byte took $took")
    }
}
