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
