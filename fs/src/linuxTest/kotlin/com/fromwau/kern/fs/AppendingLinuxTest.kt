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
import platform.posix.S_IRUSR
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

        assertEquals(Ok(AppendState.Same), before)
        assertEquals(Ok(AppendState.Moved), after)
    }

    @Test
    fun `a new file at the path does not make a moved one Same`() {
        val opened = file.openAppend().assertSuccess()

        SystemFileSystem.atomicMove(file, dir / "app.log.1")
        file.writeRaw("")
        val state = opened.state()
        opened.close()

        assertEquals(Ok(AppendState.Moved), state)
    }

    @Test
    fun `a deleted file is Deleted`() {
        val opened = file.openAppend().assertSuccess()

        SystemFileSystem.delete(file)
        val state = opened.state()
        opened.close()

        assertEquals(Ok(AppendState.Deleted), state)
    }

    @Test
    fun `onReopen is told a deleted log was Deleted`() {
        val states = mutableListOf<AppendState>()
        val log = file.openAppend(Reopen.WhenMoved(onReopen = { _, state -> states += state })).assertSuccess()

        SystemFileSystem.delete(file)
        log.append("x".encodeToByteArray()).assertSuccess()
        log.close()

        assertEquals(listOf(AppendState.Deleted), states)
    }

    @Test
    fun `onReopen is told a moved log was Moved`() {
        val states = mutableListOf<AppendState>()
        val log = file.openAppend(Reopen.WhenMoved(onReopen = { _, state -> states += state })).assertSuccess()

        SystemFileSystem.atomicMove(file, dir / "app.log.1")
        log.append("x".encodeToByteArray()).assertSuccess()
        log.close()

        assertEquals(listOf(AppendState.Moved), states)
    }

    @Test
    fun `a file whose other hard link remains is moved rather than deleted`() {
        val opened = file.openAppend().assertSuccess()
        val _ = (dir / "app.log.1").rawHardLinkTo(file)

        SystemFileSystem.delete(file)
        val state = opened.state()
        opened.close()

        assertEquals(Ok(AppendState.Moved), state)
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

        assertEquals(FileType.Fifo, error.type)
    }

    @Test
    fun `a device at the path is NotRegularFile`() {
        val error = Path("/dev/full").openAppend().assertError<FileError.NotRegularFile>()

        assertEquals(FileType.CharacterDevice, error.type)
    }

    @Test
    fun `a write past the file size limit is WriteFailed for Other in the system's words`() {
        val opened = file.openAppend().assertSuccess()

        val refused = withFileSizeLimit(bytes = 4) { opened.append("past the limit\n".encodeToByteArray()) }
        opened.close()

        val error = refused.assertError<FileError.WriteFailed>()
        assertEquals(IoCause.Other, error.cause)
        assertEquals("$file: File too large", error.exception?.message)
    }

    @Test
    fun `appending to a file that may not be written is WriteFailed for Denied`() {
        file.writeRaw("kept")
        file.setMode(S_IRUSR)
        try {
            // A runner that may write it anyway, such as root, leaves nothing to observe.
            if (file.canOpenForWriting()) return

            val failure = file.openAppend().assertError<FileError.WriteFailed>()

            assertEquals(IoCause.Denied, failure.cause)
            assertEquals("$file: Permission denied", failure.exception?.message)
        } finally {
            file.setMode(S_IRUSR or S_IWUSR)
        }
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

    @Test
    fun `the state of a file whose folder may not be searched is Inaccessible as Denied`() {
        val folder = dir / "logs"
        val opened = (folder / "app.log").openAppend().assertSuccess()
        try {
            folder.withMode(0) {
                val failure = opened.state().assertError<FileError.Inaccessible>()
                assertEquals(folder / "app.log", failure.path)
                assertEquals(IoCause.Denied, failure.cause)
            }
        } finally {
            opened.close()
        }
    }

    @Test
    fun `a log opened with ownerOnly under umask 022 is created 0600`() {
        withUmask(UMASK_022) { file.openAppend(ownerOnly = true).assertSuccess().close() }

        file.assertPermissions(0b110_000_000)
    }

    @Test
    fun `a log opened without ownerOnly under umask 022 is created 0644`() {
        withUmask(UMASK_022) { file.openAppend().assertSuccess().close() }

        file.assertPermissions(0b110_100_100)
    }
}
