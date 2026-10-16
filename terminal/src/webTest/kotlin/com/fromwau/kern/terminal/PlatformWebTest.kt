@file:OptIn(ExperimentalWasmJsInterop::class)

package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs under Node, where the test task's output is captured rather than a terminal. */
class PlatformWebTest {

    @Test
    fun `writes to both streams land`() {
        val terminal = defaultTerminal()
        terminal.out("kern terminal test: standard output\n")
        terminal.err("kern terminal test: standard error\n")
        assertEquals(Ok(Unit), terminal.writeResult())
    }

    @Test
    fun `captured output is not a terminal`() {
        val io = platformIo()
        assertEquals(false, io.isTty)
        assertEquals(false, io.errIsTty)
    }

    @Test
    fun `a stream Node marks as a TTY is a terminal`() {
        setStdoutIsTty(true)
        try {
            assertEquals(true, platformIo().isTty)
        } finally {
            setStdoutIsTty(false)
        }
    }

    @Test
    fun `a closed pipe Node reports is a broken pipe on that stream alone`() {
        val terminal = defaultTerminal()
        emitStdoutError("EPIPE")
        try {
            assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.writeResult(Stream.Out))
            assertEquals(Ok(Unit), terminal.writeResult(Stream.Err))
        } finally {
            forgetStdoutError()
        }
    }

    @Test
    fun `the environment is read from the host`() {
        assertNotNull(platformIo().env("PATH"))
    }
}

private fun setStdoutIsTty(value: Boolean): Unit = js("{ process.stdout.isTTY = value ? true : undefined; }")

// As Node does when the reader of a pipe has gone: an error event after the write returned.
private fun emitStdoutError(code: String): Unit =
    js("{ const e = new Error('write ' + code); e.code = code; process.stdout.emit('error', e); }")

private fun forgetStdoutError(): Unit = js("{ process.stdout[Symbol.for('kern.terminal.writeError')] = null; }")
