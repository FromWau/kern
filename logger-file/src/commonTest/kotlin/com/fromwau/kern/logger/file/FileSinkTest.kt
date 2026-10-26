package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.AppendFile
import com.fromwau.kern.fs.AppendState
import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.fs.createSymlink
import com.fromwau.kern.fs.fileType
import com.fromwau.kern.fs.openAppend
import com.fromwau.kern.fs.writer
import com.fromwau.kern.logger.CloseError
import com.fromwau.kern.logger.FlushError
import com.fromwau.kern.logger.Flushed
import com.fromwau.kern.logger.Pending
import com.fromwau.kern.logger.LogFormat
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.logger.Timestamp
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.map
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlin.time.TimeSource
import kotlin.time.measureTimedValue

class FileSinkTest {
    private val logger = testLogger()
    private val dir = newTempDir()
    private var sequence = 0

    @AfterTest
    fun cleanup() {
        // A close, not a removal: a removed sink would write its pending warning after the files are gone.
        val _ = logger.close()
        dir.deleteTree()
    }

    private fun tempPath(name: String): Path = Path(dir, "$name-${sequence++}")

    /**
     * What this platform reports for an open file once it is deleted: [AppendState.Deleted], [AppendState.Unknown] on
     * the JVM on macOS, or null where an open file cannot be deleted at all, as on the JVM on Windows.
     */
    private fun deletedState(): AppendState? {
        val probe = tempPath("probe")
        val opened = probe.openAppend().assertSuccess()
        try {
            SystemFileSystem.delete(probe)
        } catch (_: Exception) {
            opened.close()
            println("skipped on this platform: an open log file cannot be deleted")
            return null
        }
        return opened.state().assertSuccess().also { opened.close() }
    }

    /**
     * What this platform reports for an open file once it is moved: [AppendState.Moved], [AppendState.Unknown] on the
     * JVM without /proc, or null where an open file cannot be moved at all, as on the JVM on Windows.
     */
    private fun movedState(): AppendState? {
        val probe = tempPath("probe")
        val opened = probe.openAppend().assertSuccess()
        try {
            SystemFileSystem.atomicMove(probe, tempPath("probe-moved"))
        } catch (_: Exception) {
            opened.close()
            println("skipped on this platform: an open log file cannot be moved")
            return null
        }
        return opened.state().assertSuccess().also { opened.close() }
    }

    @Test
    fun `each entry is appended as a line of its own`() {
        val file = tempPath("append.log")
        val _ = logger.addOrFail(FileSink(file))

        logger.tag("app").i("count" to 412) { "first" }
        logger.tag("app").w { "second" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        assertEquals(listOf("first count=412", "second"), file.messages())
    }

    @Test
    fun `a sink through a writer replaces a link planted at the log name instead of writing through it`() {
        val state = tempPath("state")
        val theirs = tempPath("theirs.log")
        SystemFileSystem.sink(theirs).buffered().use { it.writeString("theirs\n") }
        SystemFileSystem.createDirectories(state)
        Path(state, "app.log").createSymlink(theirs).assertSuccess()
        val _ = logger.addOrFail(FileSink(state.writer().assertSuccess(), "app.log"))

        logger.tag("app").i { "mine" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        assertEquals("theirs\n", theirs.readRaw())
        assertEquals(Ok(FileType.Regular), Path(state, "app.log").fileType(followLinks = false))
        assertEquals(listOf("mine"), Path(state, "app.log").messages())
    }

    @Test
    fun `a missing parent directory is created`() {
        val file = Path(tempPath("nested"), "app.log")
        val _ = logger.addOrFail(FileSink(file))

        logger.tag("app").i { "into a fresh directory" }
        val _ = logger.flush()

        assertEquals(listOf("into a fresh directory"), file.messages())
    }

    @Test
    fun `a file deleted after a quiet spell starts again with only the new lines`() = runTest {
        if (deletedState() == null) return@runTest
        val file = tempPath("deleted.log")
        val _ = logger.addOrFail(FileSink(file))
        logger.tag("app").i { "old 1" }
        logger.tag("app").i { "old 2" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        realDelay(FILE_CHECK_INTERVAL * 3)
        SystemFileSystem.delete(file)
        logger.tag("app").i { "new" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        assertEquals(listOf("new"), file.messages())
        assertEquals(0, logger.sinks.value.single().health.value.dropped, "the deleted file's lines were counted")
        assertEquals(Ok(Unit), logger.close())
    }

    @Test
    fun `lines older than the check interval are never written again after a delete`() {
        if (deletedState() == null) return
        val file = tempPath("quiet.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "old"), SinkConfig())

        time += FILE_CHECK_INTERVAL + 1.milliseconds
        SystemFileSystem.delete(file)
        val _ = sink.write(entry.copy(message = "new"), SinkConfig())
        val _ = sink.close()

        assertEquals(listOf("new"), file.messages())
    }

    @Test
    fun `closing after a quiet spell does not bring back the lines of a deleted file`() {
        if (deletedState() == null) return
        val file = tempPath("quiet-close.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "old"), SinkConfig())

        time += FILE_CHECK_INTERVAL + 1.milliseconds
        SystemFileSystem.delete(file)
        assertEquals(Ok(Unit), sink.close())

        assertFalse(SystemFileSystem.exists(file))
    }

    @Test
    fun `a file moved away is left alone and a new one starts at the path`() = runTest {
        if (movedState() == null) return@runTest
        val file = tempPath("moved.log")
        val rotated = tempPath("moved.log.1")
        val _ = logger.addOrFail(FileSink(file))
        logger.tag("app").i { "before" }
        val _ = logger.flush()

        SystemFileSystem.atomicMove(file, rotated)
        realDelay(FILE_CHECK_INTERVAL)
        logger.tag("app").i { "after" }
        val _ = logger.flush()

        assertEquals(listOf("before"), rotated.messages())
        assertEquals(listOf("after"), file.messages())
    }

    @Test
    fun `entries written within the check interval of a delete reappear in the new file`() {
        val deleted = deletedState() ?: return
        val file = tempPath("window.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "before"), SinkConfig())

        SystemFileSystem.delete(file)
        time += FILE_CHECK_INTERVAL - 1.milliseconds
        val _ = sink.write(entry.copy(message = "unseen"), SinkConfig())
        assertFalse(SystemFileSystem.exists(file))

        time += 1.milliseconds
        val _ = sink.write(entry.copy(message = "after"), SinkConfig())
        val _ = sink.close()

        val expected = if (deleted == AppendState.Deleted) listOf("before", "unseen", "after") else listOf("after")
        assertEquals(expected, file.messages())
    }

    @Test
    fun `a moved file is replaced on the first write once the check interval has passed`() {
        if (movedState() == null) return
        val file = tempPath("rotate.log")
        val rotated = tempPath("rotate.log.1")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "before"), SinkConfig())
        time += FILE_CHECK_INTERVAL / 2
        val _ = sink.write(entry.copy(message = "just before"), SinkConfig())

        SystemFileSystem.atomicMove(file, rotated)
        time += FILE_CHECK_INTERVAL
        val _ = sink.write(entry.copy(message = "after"), SinkConfig())
        val _ = sink.close()

        assertEquals(listOf("before", "just before"), rotated.messages())
        assertEquals(listOf("after"), file.messages())
    }

    @Test
    fun `only the lines written since the last check reappear when the file is deleted`() {
        val deleted = deletedState() ?: return
        val file = tempPath("since-check.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "checked"), SinkConfig())
        time += FILE_CHECK_INTERVAL
        val _ = sink.write(entry.copy(message = "kept 1"), SinkConfig())
        val _ = sink.write(entry.copy(message = "kept 2"), SinkConfig())

        SystemFileSystem.delete(file)
        time += FILE_CHECK_INTERVAL
        val _ = sink.write(entry.copy(message = "after 1"), SinkConfig())
        val _ = sink.write(entry.copy(message = "after 2"), SinkConfig())
        val _ = sink.close()

        val kept = if (deleted == AppendState.Deleted) listOf("kept 1", "kept 2") else emptyList()
        assertEquals(kept + listOf("after 1", "after 2"), file.messages())
    }

    @Test
    fun `past its capacity a deleted file's oldest unwritten lines are counted as dropped`() {
        val deleted = deletedState() ?: return
        val file = tempPath("bounded.log")
        val time = TestTimeSource()
        val installed = logger.addOrFail(testFileSink(file, capacity = 3, timeSource = time))
        (1..5).forEach { index ->
            logger.tag("app").i { "line $index" }
            assertEquals(Ok(Flushed.FullyWritten), logger.flush())
        }

        SystemFileSystem.delete(file)
        time += FILE_CHECK_INTERVAL
        logger.tag("app").i { "after" }
        val flushed = logger.flush()

        if (deleted == AppendState.Deleted) {
            assertEquals(Err(FlushError.Lost(mapOf(installed.name to 2L), emptyMap())), flushed)
            assertEquals(listOf("line 3", "line 4", "line 5", "after"), file.messages())
            assertEquals(2, installed.health.value.dropped)
            assertEquals(Err(CloseError(mapOf(installed.name to listOf(SinkError.Lost(2))))), logger.close())
            assertContains(file.messages().last(), "dropped 2 entries")
        } else {
            assertEquals(Err(FlushError.Lost(mapOf(installed.name to 5L), emptyMap())), flushed)
            assertEquals(listOf("after"), file.messages())
            assertEquals(5, installed.health.value.dropped)
        }
    }

    @Test
    fun `where a delete cannot be told from a move the kept lines are counted as dropped`() {
        if (deletedState() == null) return
        val file = tempPath("cannot-tell.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time) { path -> path.openAppend().map { CannotTellDeletes(it) } }
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "lost 1" }
        logger.tag("app").i { "lost 2" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        SystemFileSystem.delete(file)
        time += FILE_CHECK_INTERVAL
        logger.tag("app").i { "after" }
        assertEquals(Err(CloseError(mapOf(installed.name to listOf(SinkError.Lost(2))))), logger.close())

        assertEquals(2, installed.health.value.dropped)
        val (after, gap) = file.messages()
        assertEquals("after", after)
        assertContains(gap, "dropped 2 entries")
    }

    @Test
    fun `closing a sink whose file was deleted writes its kept lines to a new file`() {
        if (deletedState() != AppendState.Deleted) return
        val file = tempPath("closed-deleted.log")
        val time = TestTimeSource()
        val sink = testFileSink(file, timeSource = time)
        val _ = sink.write(entry.copy(message = "kept 1"), SinkConfig())
        val _ = sink.write(entry.copy(message = "kept 2"), SinkConfig())

        SystemFileSystem.delete(file)
        val closed = sink.close()

        assertEquals(Ok(Unit), closed)
        assertEquals(listOf("kept 1", "kept 2"), file.messages())
    }

    @Test
    fun `closing a sink whose file was deleted counts the kept lines it cannot write again as dropped`() {
        if (deletedState() != AppendState.Deleted) return
        val file = tempPath("closed-unwritable.log")
        val time = TestTimeSource()
        var opens = 0
        val refusal = FileError.WriteFailed(file, IoCause.Denied, IOException("refused"))
        val sink = testFileSink(file, timeSource = time) { path ->
            if (opens++ == 0) path.openAppend() else Err(refusal)
        }
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "kept 1" }
        logger.tag("app").i { "kept 2" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        SystemFileSystem.delete(file)
        val closed = installed.close()

        val refused = SinkError.Dropped(refusal)
        assertEquals(Err(CloseError(mapOf(installed.name to listOf(refused, SinkError.Lost(2))))), closed)
        assertEquals(2, installed.health.value.dropped)
        assertEquals(refused, installed.health.value.lastError)
        assertNotNull(installed.health.value.failingSince)
    }

    @Test
    fun `a logger close fails with the error of a rewrite that failed`() {
        if (deletedState() != AppendState.Deleted) return
        val file = tempPath("logger-closed-unwritable.log")
        val time = TestTimeSource()
        var opens = 0
        val refusal = FileError.WriteFailed(file, IoCause.Denied, IOException("refused"))
        val sink = testFileSink(file, timeSource = time) { path ->
            if (opens++ == 0) path.openAppend() else Err(refusal)
        }
        val installed = logger.addOrFail(sink)
        logger.tag("app").i { "kept" }
        assertEquals(Ok(Flushed.FullyWritten), logger.flush())

        SystemFileSystem.delete(file)
        val closed = logger.close()

        val refused = SinkError.Dropped(refusal)
        assertEquals(Err(CloseError(mapOf(installed.name to listOf(refused, SinkError.Lost(1))))), closed)
        assertEquals(1, installed.health.value.dropped)
    }

    @Test
    fun `a failed append right after a delete keeps the lines it was writing again`() {
        if (deletedState() != AppendState.Deleted) return
        val file = tempPath("rewrite-failed.log")
        val time = TestTimeSource()
        var opens = 0
        val sink = testFileSink(file, timeSource = time) { path ->
            val reopenedAfterDelete = ++opens == 2
            path.openAppend().map { if (reopenedAfterDelete) FailingOnce(it) else it }
        }
        val _ = sink.write(entry.copy(message = "kept"), SinkConfig())

        SystemFileSystem.delete(file)
        time += FILE_CHECK_INTERVAL
        val failed = sink.write(entry.copy(message = "after"), SinkConfig())
        // A retry long after: the lines waiting to be written again have reached no file, so they still count.
        time += FILE_CHECK_INTERVAL * 3
        val retried = sink.write(entry.copy(message = "after"), SinkConfig())
        val _ = sink.close()

        failed.assertError<SinkError.Dropped>()
        assertEquals(Ok(Unit), retried)
        assertEquals(listOf("kept", "after"), file.messages())
    }

    @Test
    fun `flush of a file that cannot be written gives up after one attempt and close tries until its deadline`() {
        val file = tempPath("unwritable")
        SystemFileSystem.createDirectories(file)
        val installed = logger.addOrFail(FileSink(file))
        logger.tag("app").i { "lost" }

        val (flushed, flushTook) = TimeSource.Monotonic.measureTimedValue { logger.flush(5.seconds) }
        val (closed, closeTook) = TimeSource.Monotonic.measureTimedValue { logger.close(300.milliseconds) }

        val directory = SinkError.Dropped(FileError.NotRegularFile(file, FileType.Directory))
        assertEquals(Ok(Flushed.StillQueued(mapOf(installed.name to Pending.GaveUp(1, directory)))), flushed)
        assertEquals(Err(CloseError(mapOf(installed.name to listOf(SinkError.TimedOut(1))))), closed)
        assertTrue(flushTook < 2.seconds, "flush took $flushTook")
        assertTrue(closeTook in 300.milliseconds..<2.seconds, "close took $closeTook")
        assertEquals(1, installed.health.value.dropped)
    }

    @Test
    fun `under Drop a file that cannot be written loses its entries and close does not wait`() {
        val file = tempPath("unwritable-drop")
        SystemFileSystem.createDirectories(file)
        val installed = logger.addOrFail(FileSink(file, onFailure = SinkFailure.Drop()))
        logger.tag("app").i { "lost" }

        val (closed, took) = TimeSource.Monotonic.measureTimedValue { logger.close(5.seconds) }

        val directory = SinkError.Dropped(FileError.NotRegularFile(file, FileType.Directory))
        assertEquals(Err(CloseError(mapOf(installed.name to listOf(directory, SinkError.Lost(1))))), closed)
        assertTrue(took < 2.seconds, "close took $took")
        assertEquals(1, installed.health.value.dropped)
    }

    @Test
    fun `a directory at the path fails with the FileError saying so`() = runTest {
        val file = tempPath("directory")
        SystemFileSystem.createDirectories(file)
        val installed = logger.addOrFail(FileSink(file, onFailure = SinkFailure.Drop()))

        logger.tag("app").i { "lost" }
        eventually { installed.health.value.failures > 0 }

        assertEquals(
            SinkError.Dropped(FileError.NotRegularFile(file, FileType.Directory)),
            installed.health.value.lastError,
        )
    }

    @Test
    fun `FileSink is a class whose path and format can be read back`() {
        val file = tempPath("class.log")
        val _ = logger.addOrFail(FileSink(file, format = LogFormat.JSON))

        val sink = assertIs<FileSink>(logger.sinks.value.single().sink)

        assertEquals(file, sink.path)
        assertEquals(LogFormat.JSON, sink.format)
    }

    @Test
    fun `the path is checked at most once per interval`() {
        val file = tempPath("checks.log")
        val time = TestTimeSource()
        var checks = 0
        val sink = testFileSink(file, timeSource = time) { path ->
            path.openAppend().map { opened ->
                object : AppendFile by opened {
                    override fun state(): Result<AppendState, FileError> {
                        checks++
                        return opened.state()
                    }
                }
            }
        }

        val _ = sink.write(entry, SinkConfig())
        time += FILE_CHECK_INTERVAL / 2
        val _ = sink.write(entry, SinkConfig())
        assertEquals(0, checks, "no check right after opening nor within the interval")

        time += FILE_CHECK_INTERVAL / 2
        val _ = sink.write(entry, SinkConfig())
        val _ = sink.write(entry, SinkConfig())
        assertEquals(1, checks)
        val _ = sink.close()
    }

    @Test
    fun `a JSON file gets one object per line`() {
        val file = tempPath("json.log")
        val _ = logger.addOrFail(FileSink(file, format = LogFormat.JSON))

        logger.tag("app").i("count" to 412) { "structured" }
        val _ = logger.flush()

        val line = file.lines().single()
        assertTrue(line.startsWith("{"), line)
        assertContains(line, "\"fields\":{\"count\":412}")
    }

    @Test
    fun `Timestamp Off starts each text line at the level`() {
        val file = tempPath("journald.log")
        val _ = logger.addOrFail(FileSink(file), SinkConfig(timestamp = Timestamp.Off))

        logger.tag("app").i { "no stamp" }
        val _ = logger.flush()

        assertEquals("INFO    app - no stamp", file.lines().single())
    }

    @Test
    fun `configure switches the timestamp from the next entry on`() {
        val file = tempPath("switch.log")
        val sink = FileSink(file)
        val _ = logger.addOrFail(sink)

        logger.tag("app").i { "stamped" }
        val _ = logger.flush()
        logger.configureOrFail(sink) { it.copy(timestamp = Timestamp.Off) }
        logger.tag("app").i { "plain" }
        val _ = logger.flush()

        val (stamped, plain) = file.lines()
        assertTrue(stamped.first().isDigit(), stamped)
        assertEquals("INFO    app - plain", plain)
    }

    @Test
    fun `after a failed write the next entry starts on a line of its own`() {
        val file = tempPath("torn.log")
        SystemFileSystem.createDirectories(file)
        val sink = FileSink(file)
        sink.write(entry, SinkConfig()).assertError<SinkError.Dropped>()

        SystemFileSystem.delete(file)
        SystemFileSystem.sink(file).buffered().use { it.writeString("{\"torn") }
        val _ = sink.write(entry.copy(message = "readable"), SinkConfig())
        val _ = sink.close()

        val lines = file.readRaw().lines()
        assertEquals("{\"torn", lines[0])
        assertContains(lines[1], "readable")
    }

    @Test
    fun `an entry whose throwable cannot describe itself is still written`() {
        val file = tempPath("hostile.log")
        val sink = FileSink(file)

        val written = sink.write(entry.copy(throwable = HostileException()), SinkConfig())
        val _ = sink.close()

        assertEquals(Ok(Unit), written)
        assertContains(file.readRaw(), "hello")
    }

    @Test
    fun `close releases the file and the next entry reopens it`() {
        val file = tempPath("reopen.log")
        val sink = FileSink(file)

        val _ = sink.write(entry.copy(message = "one"), SinkConfig())
        assertEquals(Ok(Unit), sink.close())
        val _ = sink.write(entry.copy(message = "two"), SinkConfig())
        val _ = sink.close()

        assertEquals(listOf("one", "two"), file.messages())
    }
}

/** Reports a file that is no longer at its path as [AppendState.Unknown], as the JVM on macOS does. */
private class CannotTellDeletes(private val opened: AppendFile) : AppendFile by opened {
    override fun state(): Result<AppendState, FileError> =
        opened.state().map { if (it == AppendState.Same) it else AppendState.Unknown }
}

/** Fails its first append as a full disk would, writing nothing, and appends as usual after that. */
private class FailingOnce(private val opened: AppendFile) : AppendFile by opened {
    private var failed = false

    override fun append(bytes: ByteArray): EmptyResult<FileError> {
        if (failed) return opened.append(bytes)
        failed = true
        return Err(FileError.WriteFailed(path, IoCause.NoSpace, IOException("No space left on device")))
    }
}
