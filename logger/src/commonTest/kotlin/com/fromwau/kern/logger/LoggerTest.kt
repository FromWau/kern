package com.fromwau.kern.logger

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
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

    @AfterTest
    fun cleanup() {
        logger.close()
    }

    /** Console off so a test run stays readable; the recorder is what the assertions read. */
    private fun quiet(level: LogLevel) = LoggerConfig(level = level, console = ConsoleOutput.Off)

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
        logger.tag("Scanner").e(cause, "count" to 412, "cached" to true, "user" to null) { "scan failed" }

        val entry = recorder.entries.single()
        assertEquals(mapOf("count" to 412, "cached" to true, "user" to null), entry.fields)
        assertEquals(cause, entry.throwable)
    }

    @Test
    fun `holding more entries than the limit drops the oldest and the replay says how many`() {
        logger.holdUntilConfigured()
        repeat(1030) { index -> logger.tag("boot").i { "entry $index" } }

        assertEquals(6, logger.health.value.heldDropped)
        logger.configure(quiet(LogLevel.VERBOSE))

        val warning = recorder.entries.first()
        assertEquals(LogLevel.WARN, warning.level)
        assertContains(warning.message, "dropped the 6 oldest held entries")
        assertEquals((6..1029).map { "entry $it" }, recorder.messages().drop(1))
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
    fun `configure with a transform starts from the current config`() {
        logger.configure(quiet(LogLevel.WARN))

        logger.configure { it.copy(sinkFailure = SinkFailure.Throw) }

        assertEquals(quiet(LogLevel.WARN).copy(sinkFailure = SinkFailure.Throw), logger.state.value)
    }

    @Test
    fun `configure with a transform while holding starts from the defaults and replays`() {
        logger.holdUntilConfigured()
        logger.tag("boot").d { "held" }

        logger.configure { it.copy(level = LogLevel.DEBUG, console = ConsoleOutput.Off) }

        assertEquals(LoggerConfig(level = LogLevel.DEBUG, console = ConsoleOutput.Off), logger.state.value)
        assertEquals(listOf("held"), recorder.messages())
    }

    @Test
    fun `a field whose toString throws is written as a placeholder rather than failing the entry`() {
        val written = mutableListOf<String>()
        val rendering = Logger(sinks = listOf(recorder), console = { _, line, _ -> written += line })
        val broken = object {
            override fun toString(): String = error("no text for this")
        }

        rendering.tag("app").i("value" to broken) { "still logged" }

        assertEquals(listOf("still logged"), recorder.messages())
        assertContains(written.single(), "value=<toString() threw IllegalStateException>")
    }
}
