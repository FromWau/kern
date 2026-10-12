package com.fromwau.kern.logger

import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

// Named zones and ANSI colour: JS and Wasm need a zone database for the first and never print the second.
class StdioRenderingTest {

    private val entry = LogEntry(
        timestamp = Instant.parse("2026-08-11T12:34:56.789Z"),
        tag = "Scanner",
        level = LogLevel.INFO,
        message = "scan complete",
    )

    @Test
    fun `a text line stamps ISO 8601 local time to the millisecond with its offset`() {
        val utc = entry.toTextLine(TimeZone.UTC.asLocalClock())
        val vienna = entry.toTextLine(TimeZone.of("Europe/Vienna").asLocalClock())

        assertEquals("2026-08-11T12:34:56.789Z", utc.substringBefore(' '))
        assertEquals("2026-08-11T14:34:56.789+02:00", vienna.substringBefore(' '))
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

    @Test
    fun `colour follows the mode and AUTO follows the terminal`() {
        assertTrue(colors(LogFormat.TEXT, ColorMode.AUTO, terminalTakesColor = true))
        assertFalse(colors(LogFormat.TEXT, ColorMode.AUTO, terminalTakesColor = false))
        assertTrue(colors(LogFormat.TEXT, ColorMode.ALWAYS, terminalTakesColor = false))
        assertFalse(colors(LogFormat.TEXT, ColorMode.NEVER, terminalTakesColor = true))
        assertFalse(colors(LogFormat.JSON, ColorMode.ALWAYS, terminalTakesColor = true))
    }
}
