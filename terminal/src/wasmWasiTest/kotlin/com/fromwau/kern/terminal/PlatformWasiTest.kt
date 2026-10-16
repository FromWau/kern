package com.fromwau.kern.terminal

import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs under a WASI host, where the test task's output is captured rather than a terminal. */
class PlatformWasiTest {

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
    fun `the environment is read from the host`() {
        assertNotNull(platformIo().env("PATH"))
    }
}
