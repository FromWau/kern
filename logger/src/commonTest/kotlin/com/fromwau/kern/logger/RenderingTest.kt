package com.fromwau.kern.logger

import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Instant

class RenderingTest {

    private val entry = LogEntry(
        timestamp = Instant.parse("2026-08-11T12:34:56.789Z"),
        tag = "Scanner",
        level = LogLevel.INFO,
        message = "scan complete",
        fields = mapOf("count" to "412"),
    )

    @Test
    fun `a text line carries the level tag message and fields`() {
        val line = entry.toTextLine(TimeZone.UTC.asLocalClock())

        assertContains(line, "INFO")
        assertContains(line, "Scanner")
        assertContains(line, "scan complete")
        assertContains(line, "count=412")
    }

    @Test
    fun `a text line stamps ISO 8601 local time to the millisecond with its offset`() {
        val utc = entry.toTextLine(TimeZone.UTC.asLocalClock())
        val vienna = entry.toTextLine(TimeZone.of("Europe/Vienna").asLocalClock())

        assertEquals("2026-08-11T12:34:56.789Z", utc.substringBefore(' '))
        assertEquals("2026-08-11T14:34:56.789+02:00", vienna.substringBefore(' '))
    }

    @Test
    fun `a lone surrogate is escaped in json and replaced in text while a whole pair is kept`() {
        val damaged = entry.copy(message = "cut ${Char(0xD83D)} whole ${Char(0xD83D)}${Char(0xDE00)}")

        val json = damaged.toJsonLine()
        assertContains(json, "cut \\ud83d whole ${Char(0xD83D)}${Char(0xDE00)}")
        assertEquals(damaged.message, Json.parseToJsonElement(json).jsonObject["message"]?.jsonPrimitive?.content)
        val text = damaged.toTextLine(TimeZone.UTC.asLocalClock())
        assertContains(text, "cut ${Char(0xFFFD)} whole ${Char(0xD83D)}${Char(0xDE00)}")
    }

    @Test
    fun `a json timestamp always has nine fraction digits`() {
        val whole = entry.copy(timestamp = Instant.parse("2026-08-11T12:34:56Z"))

        assertContains(whole.toJsonLine(), "\"timestamp\":\"2026-08-11T12:34:56.000000000Z\"")
    }

    @Test
    fun `a json line nests the fields under their own key`() {
        val line = entry.toJsonLine()

        assertContains(line, "\"fields\":{\"count\":\"412\"}")
        assertContains(line, "\"level\":\"INFO\"")
        assertContains(line, "\"timestamp\":\"2026-08-11T12:34:56.789000000Z\"")
    }

    @Test
    fun `a json line stays one line even when the message spans several`() {
        val line = entry.copy(message = "he said \"hi\"\nthen left").toJsonLine()

        assertFalse(line.contains('\n'), "a shipper reads one object per line: $line")
        assertContains(line, "\\\"hi\\\"")
        assertContains(line, "\\n")
    }

    @Test
    fun `an entry without fields or a throwable omits both keys`() {
        val line = entry.copy(fields = emptyMap()).toJsonLine()

        assertFalse(line.contains("fields"))
        assertFalse(line.contains("stackTrace"))
    }

    @Test
    fun `a throwable is rendered under the message rather than folded into it`() {
        val withCause = entry.copy(throwable = IllegalStateException("boom"))

        assertContains(withCause.toTextLine(TimeZone.UTC.asLocalClock()), "scan complete")
        assertContains(withCause.toTextLine(TimeZone.UTC.asLocalClock()), "boom")
        assertContains(withCause.toJsonLine(), "\"stackTrace\"")
        assertEquals("scan complete", withCause.message)
    }

    @Test
    fun `colorize leaves the line alone when colour is disabled`() {
        assertEquals("plain line", colorize("plain line", LogLevel.ERROR, enabled = false))
    }

    @Test
    fun `colorize wraps each severity in its own colour`() {
        val esc = Char(27)
        assertEquals("$esc[31mboom$esc[0m", colorize("boom", LogLevel.ERROR, enabled = true))
        assertEquals("$esc[33mcareful$esc[0m", colorize("careful", LogLevel.WARN, enabled = true))
        // VERBOSE is the bright-black/grey slot, distinct from the plain black in the palette.
        assertEquals("$esc[90mnoise$esc[0m", colorize("noise", LogLevel.VERBOSE, enabled = true))
    }
}
