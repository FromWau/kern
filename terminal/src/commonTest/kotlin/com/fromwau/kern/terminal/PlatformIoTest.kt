package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun io(
    out: (String) -> Unit = {},
    err: (String) -> Unit = {},
    isTty: Boolean = false,
    errIsTty: Boolean = false,
    width: Int? = null,
    ansiCapable: Boolean = true,
    errAnsiCapable: Boolean = ansiCapable,
    env: Map<String, String> = emptyMap(),
    failureOf: (Stream) -> WriteError? = { null },
    afterPendingWrites: (() -> Unit) -> Unit = { it() },
) = PlatformIo(
    writeOut = out,
    writeErr = err,
    isTty = isTty,
    errIsTty = errIsTty,
    width = width,
    ansiCapable = ansiCapable,
    errAnsiCapable = errAnsiCapable,
    env = { env[it] },
    failureOf = failureOf,
    afterPendingWrites = afterPendingWrites,
)

/**
 * Assembly, not policy: that every platform's answers reach the right knob. The precedence ladders
 * themselves are pinned by TerminalPolicyTest.
 */
class PlatformIoTest {

    @Test
    fun `out and err go to their own sinks`() {
        val out = StringBuilder()
        val err = StringBuilder()
        val terminal = io(out = { out.append(it) }, err = { err.append(it) }).toTerminal()

        terminal.out("to-out")
        terminal.err("to-err")

        assertEquals("to-out", out.toString())
        assertEquals("to-err", err.toString())
    }

    @Test
    fun `standard error decides its own colour`() {
        val redirectedErr = io(isTty = true, errIsTty = false).toTerminal()
        assertTrue(redirectedErr.ansi)
        assertFalse(redirectedErr.errAnsi)

        assertTrue(io(isTty = false, errIsTty = true).toTerminal().errAnsi)
        assertTrue(io(env = mapOf("FORCE_COLOR" to "1")).toTerminal().errAnsi)
    }

    @Test
    fun `each stream says whether it is a terminal from the probe that decides its colour`() {
        val errRedirected = io(isTty = true, errIsTty = false).toTerminal()
        assertTrue(errRedirected.outIsTerminal)
        assertFalse(errRedirected.errIsTerminal)

        val outRedirected = io(isTty = false, errIsTty = true).toTerminal()
        assertFalse(outRedirected.outIsTerminal)
        assertTrue(outRedirected.errIsTerminal)
    }

    @Test
    fun `the detected width is used but COLUMNS still wins`() {
        assertEquals(120, io(width = 120).toTerminal().columns)
        assertEquals(40, io(width = 120, env = mapOf("COLUMNS" to "40")).toTerminal().columns)
    }

    @Test
    fun `an undetectable width falls back to eighty`() {
        // The JVM and Android answer null here on every run, so this is their normal path, not an edge case.
        assertEquals(80, io(width = null).toTerminal().columns)
    }

    @Test
    fun `a handle that cannot render colour gates it unless colour is forced`() {
        assertFalse(io(isTty = true, ansiCapable = false).toTerminal().ansi)
        assertTrue(io(isTty = false, ansiCapable = false, env = mapOf("FORCE_COLOR" to "1")).toTerminal().ansi)
    }

    @Test
    fun `each stream's handle decides whether that stream can render colour`() {
        val outRedirected = io(isTty = true, errIsTty = true, ansiCapable = false, errAnsiCapable = true).toTerminal()
        assertFalse(outRedirected.ansi)
        assertTrue(outRedirected.errAnsi)

        val errRedirected = io(isTty = true, errIsTty = true, ansiCapable = true, errAnsiCapable = false).toTerminal()
        assertTrue(errRedirected.ansi)
        assertFalse(errRedirected.errAnsi)
    }

    @Test
    fun `a write failure reaches the terminal as its typed error`() {
        assertEquals(Ok(Unit), io().toTerminal().writeResult())
        assertEquals(
            Err(WriteError.BrokenPipe(Stream.Out)),
            io(failureOf = { WriteError.BrokenPipe(it) }).toTerminal().writeResult(),
        )
    }

    @Test
    fun `each stream reports its own failure`() {
        val failures = mapOf(
            Stream.Out to WriteError.BrokenPipe(Stream.Out),
            Stream.Err to WriteError.Refused(Stream.Err, "No space left on device"),
        )
        val both = io(failureOf = { failures[it] }).toTerminal()
        val errOnly = io(failureOf = { if (it == Stream.Err) failures[it] else null }).toTerminal()

        assertEquals(Err(failures.getValue(Stream.Out)), both.writeResult(Stream.Out))
        assertEquals(Err(failures.getValue(Stream.Err)), both.writeResult(Stream.Err))
        assertEquals(Ok(Unit), errOnly.writeResult(Stream.Out))
        assertEquals(Err(failures.getValue(Stream.Err)), errOnly.writeResult(Stream.Err))
    }

    @Test
    fun `awaiting the write result waits for the platform and then answers`() = runTest {
        var failure: WriteError? = null
        val terminal = io(
            failureOf = { failure },
            afterPendingWrites = { landed ->
                failure = WriteError.Unknown(Stream.Out, "ENOSPC")
                landed()
            },
        ).toTerminal()

        assertEquals(Ok(Unit), terminal.writeResult())
        assertEquals(Err(WriteError.Unknown(Stream.Out, "ENOSPC")), terminal.awaitWriteResult())
    }

    @Test
    fun `the block after writes waits for the platform and gets the answer it has then`() {
        var failure: WriteError? = null
        var landed: () -> Unit = {}
        val terminal = io(failureOf = { failure }, afterPendingWrites = { landed = it }).toTerminal()
        var answer: EmptyResult<WriteError>? = null

        terminal.afterWrites { answer = it }
        assertNull(answer)

        failure = WriteError.Unknown(Stream.Out, "ENOSPC")
        landed()
        assertEquals(Err(WriteError.Unknown(Stream.Out, "ENOSPC")), answer)
    }
}
