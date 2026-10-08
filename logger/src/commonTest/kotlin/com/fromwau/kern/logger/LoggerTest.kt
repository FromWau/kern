package com.fromwau.kern.logger

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Recorder : LogSink {
    val entries: MutableList<LogEntry> = mutableListOf()

    override fun write(entry: LogEntry, config: LoggerConfig) {
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
    private fun quiet(level: LogLevel) = LoggerConfig(level = level, console = Console.OFF)

    private fun tempFile(name: String): Path {
        val path = Path(SystemTemporaryDirectory, "kern-logger-$name-${sequence++}.log")
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        created += path
        return path
    }

    private fun read(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    @Test
    fun `a new logger writes at once with the default config`() {
        val console = mutableListOf<String>()
        val fresh = Logger(sinks = listOf(recorder), console = { entry, _, _ -> console += entry.message })

        fresh.tag("app").d { "below the default INFO" }
        fresh.tag("app").i { "written at once" }

        assertEquals(listOf("written at once"), console)
        assertEquals(listOf("written at once"), recorder.messages())
        assertEquals(LoggerConfig(), fresh.state.value)
    }

    @Test
    fun `entries held until configured are replayed once the level is known`() {
        logger.holdUntilConfigured()
        logger.tag("boot").d { "reading config" }
        logger.tag("boot").i { "config read" }
        assertTrue(recorder.entries.isEmpty(), "nothing may be written while the level is unknown")

        logger.configure(quiet(LogLevel.DEBUG))

        assertEquals(listOf("reading config", "config read"), recorder.messages())
    }

    @Test
    fun `a held entry below the configured level is dropped on replay`() {
        logger.holdUntilConfigured()
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

        logger.configure(quiet(LogLevel.DEBUG))

        logger.tag("app").d { "emitted at DEBUG" }
        assertEquals(listOf("emitted at DEBUG"), recorder.messages())
    }

    @Test
    fun `state is null while holding and then mirrors every change`() {
        logger.holdUntilConfigured()
        assertNull(logger.state.value)

        logger.configure(quiet(LogLevel.INFO))
        assertEquals(LogLevel.INFO, logger.state.value?.level)

        logger.configure(quiet(LogLevel.ERROR))
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
    fun `a field key passed twice keeps its last value`() {
        logger.configure(quiet(LogLevel.INFO))

        logger.tag("app").i("id" to "first", "id" to "second") { "retrying" }

        assertEquals(mapOf("id" to "second"), recorder.entries.single().fields)
    }

    @Test
    fun `fields and the throwable reach a sink as data rather than text`() {
        logger.configure(quiet(LogLevel.INFO))

        val cause = IllegalStateException("boom")
        logger.tag("Scanner").e(cause, "count" to "412", "ms" to "1200") { "scan failed" }

        val entry = recorder.entries.single()
        assertEquals(mapOf("count" to "412", "ms" to "1200"), entry.fields)
        assertEquals(cause, entry.throwable)
    }

    @Test
    fun `holding more entries than the limit is an error naming the missing configure`() {
        logger.holdUntilConfigured()
        repeat(1024) { index -> logger.tag("boot").i { "entry $index" } }

        val overflow = assertFailsWith<IllegalStateException> { logger.tag("boot").i { "entry 1024" } }

        assertContains(overflow.message.orEmpty(), "configure()")
        logger.configure(quiet(LogLevel.VERBOSE))
        assertEquals(1024, recorder.entries.size)
    }

    @Test
    fun `close before configure writes the held entries with the defaults and then a warning`() {
        val console = mutableListOf<String>()
        val held = Logger(sinks = listOf(recorder), console = { entry, _, _ -> console += entry.message })
        held.holdUntilConfigured()
        held.tag("boot").i { "first" }
        held.tag("boot").i { "second" }

        held.close()

        assertEquals(listOf("first", "second"), recorder.messages().take(2))
        val warning = recorder.entries.last()
        assertEquals(LogLevel.WARN, warning.level)
        assertContains(warning.message, "before configure()")
        assertEquals(3, console.size)
        assertEquals(LoggerConfig(), held.state.value)
    }

    @Test
    fun `close on a hold with nothing held ends it without a warning`() {
        val console = mutableListOf<String>()
        val empty = Logger(sinks = listOf(recorder), console = { entry, _, _ -> console += entry.message })
        empty.holdUntilConfigured()

        empty.close()

        assertTrue(recorder.entries.isEmpty())
        assertTrue(console.isEmpty())
        assertEquals(LoggerConfig(), empty.state.value)
    }

    @Test
    fun `an entry reaches the sinks before the console and a replay reaches every sink first`() {
        val order = mutableListOf<String>()
        val ordered = Logger(
            sinks = listOf(LogSink { entry, _ -> order += "sink ${entry.message}" }),
            console = { entry, _, _ -> order += "console ${entry.message}" },
        )

        ordered.tag("app").i { "live" }
        assertEquals(listOf("sink live", "console live"), order)

        order.clear()
        ordered.holdUntilConfigured()
        ordered.tag("app").i { "1" }
        ordered.tag("app").i { "2" }
        ordered.configure(LoggerConfig())

        assertEquals(listOf("sink 1", "sink 2", "console 1", "console 2"), order)
    }

    @Test
    fun `a sink that throws reaches the code that logged`() {
        val broken = Logger(sinks = listOf(LogSink { _, _ -> error("sink is broken") }), console = { _, _, _ -> })
        broken.configure(quiet(LogLevel.INFO))

        val thrown = assertFailsWith<IllegalStateException> { broken.tag("app").i { "lost with the sink" } }

        assertEquals("sink is broken", thrown.message)
    }

    @Test
    fun `a file that cannot be written is skipped without a word and the sinks still get every entry`() {
        val blocker = tempFile("blocker")
        SystemFileSystem.sink(blocker).close()
        val console = mutableListOf<String>()
        val failing = Logger(sinks = listOf(recorder), console = { entry, _, _ -> console += entry.message })
        failing.configure(LoggerConfig(file = Path(blocker, "app.log")))

        failing.tag("app").i { "first" }
        failing.tag("app").i { "second" }

        assertEquals(listOf("first", "second"), recorder.messages())
        assertEquals(listOf("first", "second"), console)
    }

    @Test
    fun `after a failed write the next entry starts on a line of its own`() {
        val file = tempFile("torn")
        SystemFileSystem.createDirectories(file)
        logger.configure(quiet(LogLevel.INFO).copy(file = file))
        logger.tag("app").i { "fails: the path is a directory" }

        SystemFileSystem.delete(file)
        SystemFileSystem.sink(file).buffered().use { it.writeString("{\"torn") }
        logger.tag("app").i { "readable" }
        logger.close()

        val lines = read(file).lines()
        assertEquals("{\"torn", lines[0])
        assertContains(lines[1], "readable")
    }

    @Test
    fun `configure with a transform starts from the current config`() {
        logger.configure(quiet(LogLevel.WARN))

        logger.configure { it.copy(format = LogFormat.JSON) }

        assertEquals(quiet(LogLevel.WARN).copy(format = LogFormat.JSON), logger.state.value)
    }

    @Test
    fun `configure with a transform while holding starts from the defaults and replays`() {
        logger.holdUntilConfigured()
        logger.tag("boot").d { "held" }

        logger.configure { it.copy(level = LogLevel.DEBUG, console = Console.OFF) }

        assertEquals(LoggerConfig(level = LogLevel.DEBUG, console = Console.OFF), logger.state.value)
        assertEquals(listOf("held"), recorder.messages())
    }

    @Test
    fun `the configured file receives the rendered line`() {
        val file = tempFile("basic")
        logger.configure(quiet(LogLevel.INFO).copy(file = file))

        logger.tag("app").i("count" to "412") { "written to disk" }
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
        logger.configure(quiet(LogLevel.INFO).copy(file = second))
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
