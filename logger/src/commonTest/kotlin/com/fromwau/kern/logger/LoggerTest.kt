package com.fromwau.kern.logger

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recorder : LogSink {
    val entries: MutableList<LogEntry> = mutableListOf()

    override fun write(entry: LogEntry, state: LoggerRuntimeState) {
        entries += entry
    }

    fun messages(): List<String> = entries.map { it.message }
}

class LoggerTest {

    private val recorder = Recorder()
    private val logger = Logger(sinks = listOf(recorder))

    private val created = mutableListOf<Path>()
    private var sequence = 0

    @AfterTest
    fun cleanup() {
        logger.close()
        created.forEach { if (SystemFileSystem.exists(it)) SystemFileSystem.delete(it) }
    }

    /** Console off so a test run stays readable; the recorder is what the assertions read. */
    private fun quiet(level: LogLevel) = LoggerRuntimeState(level = level, console = false)

    private fun tempFile(name: String): Path {
        val path = Path(SystemTemporaryDirectory, "kern-logger-$name-${sequence++}.log")
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        created += path
        return path
    }

    private fun read(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    @Test
    fun `entries logged before configure are replayed once the level is known`() {
        logger.tag("boot").d { "reading config" }
        logger.tag("boot").i { "config read" }
        assertTrue(recorder.entries.isEmpty(), "nothing may be written while the level is unknown")

        logger.configure(quiet(LogLevel.DEBUG))

        assertEquals(listOf("reading config", "config read"), recorder.messages())
    }

    @Test
    fun `a buffered entry below the configured level is dropped on replay`() {
        logger.tag("boot").d { "debug detail" }
        logger.tag("boot").w { "a warning" }

        logger.configure(quiet(LogLevel.WARN))

        assertEquals(listOf("a warning"), recorder.messages())
    }

    @Test
    fun `a level change applies to the next entry without re-initializing`() {
        logger.configure(quiet(LogLevel.INFO))

        logger.tag("app").d { "suppressed at INFO" }
        assertTrue(recorder.entries.isEmpty())

        logger.update { it.copy(level = LogLevel.DEBUG) }

        logger.tag("app").d { "emitted at DEBUG" }
        assertEquals(listOf("emitted at DEBUG"), recorder.messages())
    }

    @Test
    fun `state is null until configured and then mirrors every change`() {
        assertNull(logger.state.value)

        logger.configure(quiet(LogLevel.INFO))
        assertEquals(LogLevel.INFO, logger.state.value?.level)

        logger.update { it.copy(level = LogLevel.ERROR) }
        assertEquals(LogLevel.ERROR, logger.state.value?.level)
    }

    @Test
    fun `a filtered entry never builds its message`() {
        logger.configure(quiet(LogLevel.ERROR))

        var built = 0
        logger.tag("app").d {
            built++
            "expensive to build"
        }

        assertEquals(0, built)
    }

    @Test
    fun `fields and the throwable reach a sink as data rather than text`() {
        logger.configure(quiet(LogLevel.INFO))

        val cause = IllegalStateException("boom")
        logger.tag("Scanner").e(cause, "count" to 412, "ms" to 1200) { "scan failed" }

        val entry = recorder.entries.single()
        assertEquals(mapOf("count" to "412", "ms" to "1200"), entry.fields)
        assertEquals(cause, entry.throwable)
    }

    @Test
    fun `the pre-configure buffer is bounded and drops the oldest entries`() {
        repeat(1030) { index -> logger.tag("boot").i { "entry $index" } }

        logger.configure(quiet(LogLevel.VERBOSE))

        assertEquals(1024, recorder.entries.size)
        assertEquals("entry 6", recorder.messages().first())
        assertEquals("entry 1029", recorder.messages().last())
    }

    @Test
    fun `a sink that throws does not reach the code that logged`() {
        val broken = Logger(sinks = listOf(LogSink { _, _ -> error("sink is broken") }, recorder))
        broken.configure(quiet(LogLevel.INFO))

        broken.tag("app").i { "still logged" }

        assertEquals(listOf("still logged"), recorder.messages())
    }

    @Test
    fun `the configured file receives the rendered line`() {
        val file = tempFile("basic")
        logger.configure(quiet(LogLevel.INFO).copy(file = file))

        logger.tag("app").i("count" to 412) { "written to disk" }
        logger.close()

        val written = read(file)
        assertContains(written, "written to disk")
        assertContains(written, "count=412")
    }

    @Test
    fun `moving the configured file writes the next entry to the new path`() {
        val first = tempFile("first")
        val second = tempFile("second")
        logger.configure(quiet(LogLevel.INFO).copy(file = first))

        logger.tag("app").i { "in the first file" }
        logger.update { it.copy(file = second) }
        logger.tag("app").i { "in the second file" }
        logger.close()

        assertContains(read(first), "in the first file")
        assertContains(read(second), "in the second file")
        assertFalse(read(first).contains("in the second file"))
    }

    @Test
    fun `a missing parent directory is created rather than failing the write`() {
        val nested = Path(SystemTemporaryDirectory, "kern-logger-nested-${sequence++}")
        val file = Path(nested, "app.log")
        created += file
        logger.configure(quiet(LogLevel.INFO).copy(file = file))

        logger.tag("app").i { "into a fresh directory" }
        logger.close()

        assertContains(read(file), "into a fresh directory")

        SystemFileSystem.delete(file)
        SystemFileSystem.delete(nested)
    }
}
