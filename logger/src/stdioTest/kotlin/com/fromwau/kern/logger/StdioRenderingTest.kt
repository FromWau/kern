package com.fromwau.kern.logger

import com.fromwau.kern.result.Err
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

// Named zones and ANSI colour: JS and Wasm need a zone database for the first and never print the second.
class StdioRenderingTest {

    private fun coloured(text: Styled): String = RecordingTerminal(ansi = true).also { it.out(text) }.out

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
        val terminal = RecordingTerminal(ansi = false)
        terminal.out(colorize("plain line", LogLevel.ERROR))
        assertEquals("plain line", terminal.out)
    }

    @Test
    fun `colorize wraps each severity in its own colour`() {
        val esc = Char(27)
        assertEquals("$esc[31mboom$esc[0m", coloured(colorize("boom", LogLevel.ERROR)))
        assertEquals("$esc[33mcareful$esc[0m", coloured(colorize("careful", LogLevel.WARN)))
        // VERBOSE is the bright-black/grey slot, distinct from the plain black in the palette.
        assertEquals("$esc[90mnoise$esc[0m", coloured(colorize("noise", LogLevel.VERBOSE)))
    }

    @Test
    fun `a console line takes colour from its own stream under AUTO`() {
        val esc = Char(27)
        val terminal = RecordingTerminal(ansi = false, errAnsi = true)

        val _ = streamWriter(Stream.Out, LogFormat.TEXT, ColorMode.AUTO, terminal)(entry, SinkConfig())
        val _ = streamWriter(Stream.Err, LogFormat.TEXT, ColorMode.AUTO, terminal)(entry, SinkConfig())

        assertFalse(esc in terminal.out)
        assertTrue(terminal.err.startsWith("$esc[37m"))
        assertTrue(terminal.err.endsWith("scan complete$esc[0m\n"))
    }

    @Test
    fun `a console line follows ALWAYS and NEVER whatever the terminal says`() {
        val esc = Char(27)
        val noColour = RecordingTerminal()
        val colour = RecordingTerminal(ansi = true)

        val _ = streamWriter(Stream.Out, LogFormat.TEXT, ColorMode.ALWAYS, noColour)(entry, SinkConfig())
        val _ = streamWriter(Stream.Out, LogFormat.TEXT, ColorMode.NEVER, colour)(entry, SinkConfig())

        assertTrue(noColour.out.startsWith("$esc[37m"))
        assertFalse(esc in colour.out)
    }

    @Test
    fun `colour follows the mode and AUTO follows the terminal`() {
        assertTrue(colors(LogFormat.TEXT, ColorMode.AUTO, terminalTakesColor = true))
        assertFalse(colors(LogFormat.TEXT, ColorMode.AUTO, terminalTakesColor = false))
        assertTrue(colors(LogFormat.TEXT, ColorMode.ALWAYS, terminalTakesColor = false))
        assertFalse(colors(LogFormat.TEXT, ColorMode.NEVER, terminalTakesColor = true))
        assertFalse(colors(LogFormat.JSON, ColorMode.ALWAYS, terminalTakesColor = true))
    }

    @Test
    fun `a standard error sink reports its own failure even after standard output failed`() {
        val failures = mapOf(
            Stream.Out to WriteError.BrokenPipe(Stream.Out),
            Stream.Err to WriteError.Refused(Stream.Err, "No space left on device"),
        )
        val bothFailed = RecordingTerminal()
        failures.values.forEach { bothFailed.failWith(it) }
        val write = streamWriter(Stream.Err, LogFormat.TEXT, ColorMode.NEVER, bothFailed)

        assertEquals(Err(SinkError.Dropped(failures.getValue(Stream.Err))), write(entry, SinkConfig()))
    }
}
