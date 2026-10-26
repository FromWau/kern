package com.fromwau.kern.logger

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Styled
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.out
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.atomicfu.locks.ReentrantLock
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

// Named zones, ANSI colour and threads: JS and Wasm need a zone database for the first, never print the second and
// have one thread.
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

        val _ = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.AUTO) { terminal }.write(entry, SinkConfig())
        val _ = ConsoleWriter(Stream.Err, LogFormat.TEXT, ColorMode.AUTO) { terminal }.write(entry, SinkConfig())

        assertFalse(esc in terminal.out)
        assertTrue(terminal.err.startsWith("$esc[37m"))
        assertTrue(terminal.err.endsWith("scan complete$esc[0m\n"))
    }

    @Test
    fun `a console line follows ALWAYS and NEVER whatever the terminal says`() {
        val esc = Char(27)
        val noColour = RecordingTerminal()
        val colour = RecordingTerminal(ansi = true)

        val _ = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.ALWAYS) { noColour }.write(entry, SinkConfig())
        val _ = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.NEVER) { colour }.write(entry, SinkConfig())

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
        val writer = ConsoleWriter(Stream.Err, LogFormat.TEXT, ColorMode.NEVER) { bothFailed }

        assertEquals(Err(SinkError.Dropped(failures.getValue(Stream.Err))), writer.write(entry, SinkConfig()))
    }

    @Test
    fun `a console writer whose stream failed drops each entry until it is closed and then writes again`() {
        val broken = WriteError.BrokenPipe(Stream.Out)
        val failed = RecordingTerminal().apply { failWith(broken) }
        val fresh = RecordingTerminal()
        val terminals = ArrayDeque(listOf(failed, fresh))
        val writer = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.NEVER) { terminals.removeFirst() }

        assertEquals(Err(SinkError.Dropped(broken)), writer.write(entry, SinkConfig()))
        assertEquals(Err(SinkError.Dropped(broken)), writer.write(entry, SinkConfig()))
        assertEquals(Ok(Unit), writer.close())

        assertEquals(Ok(Unit), writer.write(entry, SinkConfig()))
        assertTrue(fresh.out.endsWith("scan complete\n"))
    }

    @Test
    fun `two console sinks writing one stream at once write whole lines`() = runTest {
        val shared = StringBuilder()
        val lock = reentrantLock()
        val messages = listOf("first sink", "second sink")
        val whole = messages.map { message ->
            val recording = RecordingTerminal()
            val _ = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.NEVER) { recording }
                .write(entry.copy(message = message), SinkConfig())
            recording.out.removeSuffix("\n")
        }

        withContext(Dispatchers.Default) {
            messages.map { message ->
                val writer = ConsoleWriter(Stream.Out, LogFormat.TEXT, ColorMode.NEVER) { CharByChar(shared, lock) }
                launch { repeat(LINES) { val _ = writer.write(entry.copy(message = message), SinkConfig()) } }
            }.joinAll()
        }

        val lines = shared.toString().removeSuffix("\n").split("\n")
        assertEquals(2 * LINES, lines.size)
        assertEquals(emptyList(), lines.filter { it !in whole })
    }

    /** A terminal of its own that puts each character of a write into [shared] by itself, as one stream may split it. */
    private class CharByChar(
        private val shared: StringBuilder,
        private val lock: ReentrantLock,
    ) : Terminal {
        override fun out(text: String) = text.forEach { char -> lock.withLock { shared.append(char) } }

        override fun err(text: String) = out(text)
    }

    private companion object {
        const val LINES = 300
    }
}
