@file:OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)

package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrNull
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import platform.posix.ENXIO
import platform.posix.O_NONBLOCK
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.errno
import platform.posix.open
import platform.posix.usleep
import platform.posix.write

class StreamReadsLinuxTest {
    private val dir = newTempDir()
    private val fifo = (dir / "pipe").makeFifo()
    private val device = Path("/dev/null")
    private val worker = Worker.start()

    @AfterTest
    fun cleanUp() {
        worker.requestTermination().result
        dir.deleteTree()
    }

    /** What [read] answers while the worker writes [bytes] into the FIFO once a reader has it open, and closes it. */
    private fun <T> readWhileWriting(
        bytes: ByteArray,
        read: () -> T,
    ): T {
        val writing = worker.execute(TransferMode.SAFE, { FifoWrite(fifo.toString(), bytes) }) { it.run() }
        val answer = read()
        writing.result
        return answer
    }

    @Test
    fun `a FIFO read with streams is read to the end its writer closes`() {
        val read = readWhileWriting("port = 1".encodeToByteArray()) { fifo.readText(streams = true) }

        assertEquals(Ok("port = 1"), read)
    }

    @Test
    fun `a FIFO read with streams and trusted is read when nobody else may change it or its folder`() {
        val read = readWhileWriting("ok".encodeToByteArray()) { fifo.readText(trusted = true, streams = true) }

        assertEquals(Ok("ok"), read)
    }

    @Test
    fun `a FIFO read with streams is TooLarge once more than maxBytes arrive`() {
        val read = readWhileWriting(ByteArray(64)) { fifo.readBytes(maxBytes = 8, streams = true) }

        assertEquals(Err(FileError.TooLarge(fifo, 8, 9)), read)
    }

    @Test
    fun `a character device read with streams reads what it gives`() {
        assertContentEquals(ByteArray(0), device.readBytes(streams = true).assertSuccess())
    }

    @Test
    fun `a FIFO and a device are NotRegularFile without streams`() {
        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.readText())
        assertEquals(Err(FileError.NotRegularFile(device, FileType.CharacterDevice)), device.readBytes())
    }

    @Test
    fun `a folder and a socket are NotRegularFile with streams`() {
        val socket = (dir / "sock").makeSocket()

        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readText(streams = true))
        assertEquals(Err(FileError.NotRegularFile(socket, FileType.Socket)), socket.readText(streams = true))
    }

    @Test
    fun `a block device is NotRegularFile with streams`() {
        val block = firstBlockDevice() ?: return println("skipped: no block device in /dev")

        assertEquals(Err(FileError.NotRegularFile(block, FileType.BlockDevice)), block.readBytes(streams = true))
    }

    @Test
    fun `a writer reads a FIFO with streams`() {
        val writer = dir.writer().assertSuccess()

        val read = readWhileWriting("x".encodeToByteArray()) { writer.readText("pipe", streams = true) }

        assertEquals(Ok("x"), read)
    }

    @Test
    fun `a writer read of a folder with streams is NotRegularFile`() {
        createDirectoriesRaw(dir / "folder")
        val writer = dir.writer().assertSuccess()

        val expected = Err(FileError.NotRegularFile(writer.root / "folder", FileType.Directory))
        assertEquals(expected, writer.readBytes("folder", streams = true))
    }

    @Test
    fun `a FIFO read with streams and trusted is Refused once its writer came when others may write it`() {
        fifo.setMode(0b110_110_010)

        val read = readWhileWriting(ByteArray(0)) { fifo.readBytes(trusted = true, streams = true) }

        val refused = read.assertError<FileError.Refused>()
        assertEquals(RefusedBecause.OthersMayWrite, refused.why)
        assertEquals(fifo, refused.asked)
    }

    @Test
    fun `a FIFO read with streams within maxBytes is read`() {
        val read = readWhileWriting("ab".encodeToByteArray()) { fifo.readText(maxBytes = 3, streams = true) }

        assertEquals(Ok("ab"), read)
    }

    @Test
    fun `a FIFO read with streams past maxBytes is TooLarge by the bytes that came`() {
        val read = readWhileWriting(ByteArray(10)) { fifo.readBytes(maxBytes = 3, streams = true) }

        assertEquals(Err(FileError.TooLarge(fifo, 3, 4)), read)
    }

    @Test
    fun `a FIFO read with streams and a negative maxBytes is TooLarge by the bytes that came`() {
        val read = readWhileWriting(ByteArray(2)) { fifo.readBytes(maxBytes = -5, streams = true) }

        assertEquals(Err(FileError.TooLarge(fifo, -5, 0)), read)
    }

    @Test
    fun `a writer read of a socket with streams is NotRegularFile`() {
        (dir / "sock").makeSocket()
        val writer = dir.writer().assertSuccess()

        val expected = Err(FileError.NotRegularFile(writer.root / "sock", FileType.Socket))
        assertEquals(expected, writer.readBytes("sock", streams = true))
    }

    @Test
    fun `the terminal read with streams by a process without one is Inaccessible`() {
        val terminal = Path("/dev/tty")
        if (!SystemFileSystem.exists(terminal)) return println("skipped: no /dev/tty")
        if (hasControllingTerminal()) {
            val log = dir / "child.log"
            val status = runTestInNewSession(TERMINAL_TEST, log)
            val output = log.readText().getOrNull()
            assertEquals(0, status, "the test run without a terminal failed:\n$output")
            return
        }

        val error = terminal.readBytes(streams = true).assertError<FileError.Inaccessible>()
        assertEquals(terminal, error.path)
    }

    /** Writes [bytes] into the FIFO at [path] once something reads it, giving up after a while so no test hangs. */
    private class FifoWrite(
        val path: String,
        val bytes: ByteArray,
    ) {
        fun run() {
            val deadline = TimeSource.Monotonic.markNow() + 5.seconds
            var descriptor = open(path, O_WRONLY or O_NONBLOCK)
            while (descriptor < 0 && errno == ENXIO && deadline.hasNotPassedNow()) {
                usleep(1_000u)
                descriptor = open(path, O_WRONLY or O_NONBLOCK)
            }
            if (descriptor < 0) return
            val written = if (bytes.isEmpty()) {
                0L
            } else {
                bytes.usePinned { write(descriptor, it.addressOf(0), bytes.size.convert()) }
            }
            close(descriptor)
            check(written == bytes.size.toLong()) { "wrote $written of ${bytes.size} bytes" }
        }
    }

    private companion object {
        const val TERMINAL_TEST = "com.fromwau.kern.fs.StreamReadsLinuxTest." +
            "the terminal read with streams by a process without one is Inaccessible"
    }
}
