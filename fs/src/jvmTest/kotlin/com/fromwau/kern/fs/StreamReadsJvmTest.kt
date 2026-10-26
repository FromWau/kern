package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.mkfifo
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.io.files.Path
import org.junit.jupiter.api.Assumptions.assumeTrue

class StreamReadsJvmTest {
    private val dir = newTempDir()
    private val fifo = dir / "pipe"
    private val device = Path("/dev/null")

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private fun makeFifo() {
        assumeTrue(hasUnixView, "no unix attributes to tell a FIFO by")
        assumeTrue(fifo.mkfifo() is Result.Success, "no mkfifo")
    }

    /** What [read] answers while another thread writes [bytes] into the FIFO and closes it. */
    private fun <T> readWhileWriting(
        bytes: ByteArray,
        read: () -> T,
    ): T {
        // Daemon threads and bounded joins keep a read that never ends from hanging the test run.
        val writing = thread(isDaemon = true) {
            Files.newOutputStream(Paths.get(fifo.toString())).use { it.write(bytes) }
        }
        var answer: kotlin.Result<T>? = null
        val reader = thread(isDaemon = true) { answer = runCatching(read) }
        reader.join(FIFO_WAIT_MILLIS)
        if (reader.isAlive) fail("the read of the FIFO never ended")
        writing.join(FIFO_WAIT_MILLIS)
        return answer!!.getOrThrow()
    }

    @Test
    fun `a FIFO read with streams is read to the end its writer closes`() {
        makeFifo()

        val read = readWhileWriting("port = 1".encodeToByteArray()) { fifo.readText(streams = true) }

        assertEquals(Ok("port = 1"), read)
    }

    @Test
    fun `a FIFO read with streams and trusted is read when nobody else may change it or its folder`() {
        makeFifo()

        val read = readWhileWriting("ok".encodeToByteArray()) { fifo.readText(trusted = true, streams = true) }

        assertEquals(Ok("ok"), read)
    }

    @Test
    fun `a FIFO read with streams is TooLarge once more than maxBytes arrive`() {
        makeFifo()

        val read = readWhileWriting(ByteArray(64)) { fifo.readBytes(maxBytes = 8, streams = true) }

        assertEquals(Err(FileError.TooLarge(fifo, 8, 9)), read)
    }

    @Test
    fun `a FIFO read with streams and trusted is Refused once its writer came when others may write it`() {
        makeFifo()
        Files.setPosixFilePermissions(Paths.get(fifo.toString()), PosixFilePermissions.fromString("rw-rw--w-"))

        val read = readWhileWriting(ByteArray(0)) { fifo.readBytes(trusted = true, streams = true) }

        val refused = read.assertError<FileError.Refused>()
        assertEquals(RefusedBecause.OthersMayWrite, refused.why)
        assertEquals(fifo, refused.asked)
    }

    @Test
    fun `a FIFO read with streams and a negative maxBytes is TooLarge by the bytes that came`() {
        makeFifo()

        val read = readWhileWriting(ByteArray(2)) { fifo.readBytes(maxBytes = -5, streams = true) }

        assertEquals(Err(FileError.TooLarge(fifo, -5, 0)), read)
    }

    @Test
    fun `a character device read with streams reads what it gives`() {
        assumeTrue(hasUnixView, "no unix attributes to tell a device by")

        assertContentEquals(ByteArray(0), device.readBytes(streams = true).assertSuccess())
    }

    @Test
    fun `a FIFO and a device are NotRegularFile without streams`() {
        makeFifo()

        assertEquals(Err(FileError.NotRegularFile(fifo, FileType.Fifo)), fifo.readText())
        assertEquals(Err(FileError.NotRegularFile(device, FileType.CharacterDevice)), device.readBytes())
    }

    @Test
    fun `a folder and a socket are NotRegularFile with streams`() {
        assumeTrue(hasUnixView, "no unix attributes to tell a socket by")
        val socket = dir / "sock"
        ServerSocketChannel
            .open(StandardProtocolFamily.UNIX)
            .use { it.bind(UnixDomainSocketAddress.of(socket.toString())) }

        assertEquals(Err(FileError.NotRegularFile(dir, FileType.Directory)), dir.readText(streams = true))
        assertEquals(Err(FileError.NotRegularFile(socket, FileType.Socket)), socket.readText(streams = true))
    }

    @Test
    fun `a block device is NotRegularFile with streams`() {
        val block = firstBlockDevice().also { assumeTrue(it != null, "no block device in /dev to read") } ?: return

        assertEquals(Err(FileError.NotRegularFile(block, FileType.BlockDevice)), block.readBytes(streams = true))
    }

    @Test
    fun `a writer reads a FIFO with streams`() {
        makeFifo()
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

    private companion object {
        const val FIFO_WAIT_MILLIS = 5_000L
    }
}
