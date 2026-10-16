package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.openAppend
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.map
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.measureTimedValue

class FileSinkJvmTest {
    private val dir = newTempDir()
    private val file = Path(dir, "app.log")

    @AfterTest
    fun cleanup() {
        dir.deleteTree()
    }

    @Test
    fun `two loggers appending to one file never tear an entry`() {
        // Well past an 8 KB buffer, so an entry written in more than one call would interleave and tear.
        val message = "x".repeat(20_000)
        val closed = arrayOfNulls<Any>(2)
        val writers = List(2) { index ->
            val logger = testLogger()
            val _ = logger.addOrFail(FileSink(file, format = LogFormat.JSON))
            thread {
                repeat(300) { logger.tag("writer$index").i { message } }
                closed[index] = logger.close()
            }
        }
        writers.forEach { it.join() }
        assertEquals(listOf<Any?>(Ok(Unit), Ok(Unit)), closed.toList())

        val lines = File(file.toString()).readLines()

        assertEquals(600, lines.size)
        lines.forEach { val _ = Json.parseToJsonElement(it) }
    }

    @Test
    fun `a device at the path fails with NotRegularFile`() {
        val device = Path("/dev/full")
        assumeTrue(File(device.toString()).exists(), "no /dev/full outside Linux")
        val sink = FileSink(device)

        val written = sink.write(entry, SinkConfig())
        val _ = sink.close()

        assertEquals(Err(SinkError.Dropped(FileError.NotRegularFile(device, FileType.Other))), written)
    }

    @Test
    fun `after a failed write a file the sink may not read is still appended to`() {
        File(file.toString()).writeText("torn")
        Files.setPosixFilePermissions(Paths.get(file.toString()), PosixFilePermissions.fromString("-w-------"))
        assumeTrue(!Files.isReadable(Paths.get(file.toString())), "this user reads files without read permission")
        val sink = failingOnce()

        sink.write(entry, SinkConfig()).assertError<SinkError>()
        val retried = sink.write(entry, SinkConfig())
        val _ = sink.close()

        assertEquals(Ok(Unit), retried)
    }

    @Test
    fun `the torn-line check after a failed write reads only the end of the file`() {
        // Sparse, so it costs no disk, but reading it through would take far longer than the limit below.
        RandomAccessFile(file.toString(), "rw").use { it.setLength(256L shl 30) }
        val sink = failingOnce()

        sink.write(entry, SinkConfig()).assertError<SinkError>()
        val (retried, took) = measureTimedValue { sink.write(entry, SinkConfig()) }
        val _ = sink.close()

        assertEquals(Ok(Unit), retried)
        assertTrue(took < 1.seconds, "the check took $took")
    }

    @Test
    fun `a file rotated by a hard link and a delete keeps its lines and they are not written again`() {
        assumeTrue(File("/proc/self/fd").isDirectory, "no /proc to read the open file's link count through")
        val rotated = Path(dir, "app.log.1")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "kept"), SinkConfig())

        Files.createLink(Paths.get(rotated.toString()), Paths.get(file.toString()))
        Files.delete(Paths.get(file.toString()))
        time += FILE_CHECK_INTERVAL
        val _ = sink.write(entry.copy(message = "after"), SinkConfig())
        val _ = sink.close()

        assertEquals(listOf("kept"), rotated.messages())
        assertEquals(listOf("after"), file.messages())
    }

    /** A sink whose first append fails, as a full disk would, leaving the torn-line check for the next write. */
    private fun failingOnce(): FileSink {
        var fail = true
        return testFileSink(file) { path ->
            path.openAppend().map { opened ->
                object : AppendFile by opened {
                    override fun append(bytes: ByteArray): EmptyResult<FileError> {
                        if (!fail) return opened.append(bytes)
                        fail = false
                        return Err(FileError.WriteFailed(path, "refused"))
                    }
                }
            }
        }
    }
}
