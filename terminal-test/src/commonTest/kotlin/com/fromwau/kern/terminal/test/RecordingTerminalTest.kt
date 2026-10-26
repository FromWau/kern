package com.fromwau.kern.terminal.test

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.WriteError
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingTerminalTest {

    @Test
    fun `each stream keeps what it was sent`() {
        val terminal = RecordingTerminal()

        terminal.out("a")
        terminal.err("b")
        terminal.out("c")

        assertEquals("ac", terminal.out)
        assertEquals("b", terminal.err)
        assertEquals(Ok(Unit), terminal.writeResult())
    }

    @Test
    fun `writes keeps both streams in the order they were written`() {
        val terminal = RecordingTerminal()

        terminal.out("a")
        terminal.err("b")
        terminal.out("c")
        terminal.err("d")

        assertEquals(
            listOf(Write(Stream.Out, "a"), Write(Stream.Err, "b"), Write(Stream.Out, "c"), Write(Stream.Err, "d")),
            terminal.writes,
        )
        assertEquals("ac", terminal.out)
        assertEquals("bd", terminal.err)
    }

    @Test
    fun `writes leaves out the writes a failed stream lost`() {
        val terminal = RecordingTerminal()
        terminal.out("kept")

        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.out("lost")
        terminal.err("still written")

        assertEquals(listOf(Write(Stream.Out, "kept"), Write(Stream.Err, "still written")), terminal.writes)
    }

    @Test
    fun `writes is a snapshot a later write does not change`() {
        val terminal = RecordingTerminal()
        terminal.out("first")

        val before = terminal.writes
        terminal.err("second")

        assertEquals(listOf(Write(Stream.Out, "first")), before)
        assertEquals(listOf(Write(Stream.Out, "first"), Write(Stream.Err, "second")), terminal.writes)
    }

    @Test
    fun `a new recording terminal claims no terminal colour or width`() {
        val terminal = RecordingTerminal()

        assertEquals(0, terminal.columns)
        assertFalse(terminal.ansi)
        assertFalse(terminal.errAnsi)
        assertFalse(terminal.outIsTerminal)
        assertFalse(terminal.errIsTerminal)
    }

    @Test
    fun `it answers what it was built with`() {
        val terminal = RecordingTerminal(
            columns = 100,
            ansi = true,
            errAnsi = true,
            outIsTerminal = true,
            errIsTerminal = true,
        )

        assertEquals(100, terminal.columns)
        assertTrue(terminal.ansi)
        assertTrue(terminal.errAnsi)
        assertTrue(terminal.outIsTerminal)
        assertTrue(terminal.errIsTerminal)
    }

    @Test
    fun `a failed stream loses the writes after the failure and keeps the ones before`() {
        val terminal = RecordingTerminal()
        terminal.out("kept")

        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.out(" lost")
        terminal.err("still written")

        assertEquals("kept", terminal.out)
        assertEquals("still written", terminal.err)
    }

    @Test
    fun `lostWrites keeps what a failed stream lost in order while the other stream writes on`() {
        val terminal = RecordingTerminal()

        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.out("first")
        terminal.err("still written")
        terminal.out("second")

        assertEquals(listOf(Write(Stream.Out, "first"), Write(Stream.Out, "second")), terminal.lostWrites)
        assertEquals(listOf(Write(Stream.Err, "still written")), terminal.writes)
        assertEquals("", terminal.out)
        assertEquals("still written", terminal.err)
    }

    @Test
    fun `writes made before a stream fails are not lost`() {
        val terminal = RecordingTerminal()
        terminal.err("before")

        terminal.failWith(WriteError.Refused(Stream.Err, "No space left on device"))
        terminal.err("after")

        assertEquals(listOf(Write(Stream.Err, "after")), terminal.lostWrites)
        assertEquals("before", terminal.err)
    }

    @Test
    fun `lostWrites is a snapshot a later lost write does not change`() {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.out("first")

        val before = terminal.lostWrites
        terminal.out("second")

        assertEquals(listOf(Write(Stream.Out, "first")), before)
        assertEquals(listOf(Write(Stream.Out, "first"), Write(Stream.Out, "second")), terminal.lostWrites)
    }

    @Test
    fun `a failure is reported for its own stream`() {
        val terminal = RecordingTerminal()

        terminal.failWith(WriteError.Refused(Stream.Err, "No space left on device"))

        assertEquals(Err(WriteError.Refused(Stream.Err, "No space left on device")), terminal.writeResult())
        assertEquals(Err(WriteError.Refused(Stream.Err, "No space left on device")), terminal.writeResult(Stream.Err))
        assertEquals(Ok(Unit), terminal.writeResult(Stream.Out))
    }

    @Test
    fun `a later failure for the same stream replaces the earlier one`() {
        val terminal = RecordingTerminal()

        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.failWith(WriteError.Refused(Stream.Out, "No space left on device"))

        assertEquals(Err(WriteError.Refused(Stream.Out, "No space left on device")), terminal.writeResult(Stream.Out))
        assertEquals(Err(WriteError.Refused(Stream.Out, "No space left on device")), terminal.writeResult())
    }

    @Test
    fun `standard output's failure comes before standard error's and neither hides the other`() {
        val terminal = RecordingTerminal()

        terminal.failWith(WriteError.Refused(Stream.Err, "No space left on device"))
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))

        assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.writeResult())
        assertEquals(Err(WriteError.Refused(Stream.Err, "No space left on device")), terminal.writeResult(Stream.Err))
    }

    @Test
    fun `awaiting the write result answers at once`() = runTest {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))

        assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.awaitWriteResult())
    }
}
