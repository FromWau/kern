package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun io(
    out: (String) -> Unit = {},
    err: (String) -> Unit = {},
    isTty: Boolean = false,
    width: Int? = null,
    ansiCapable: Boolean = true,
    env: Map<String, String> = emptyMap(),
    writeFailure: () -> WriteError? = { null },
) = PlatformIo(
    writeOut = out,
    writeErr = err,
    isTty = isTty,
    width = width,
    ansiCapable = ansiCapable,
    env = { env[it] },
    writeFailure = writeFailure,
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
    fun `a write failure reaches the terminal as its typed error`() {
        assertEquals(Ok(Unit), io().toTerminal().writeResult())
        assertEquals(
            Err(WriteError.BrokenPipe),
            io(writeFailure = { WriteError.BrokenPipe }).toTerminal().writeResult(),
        )
    }
}
