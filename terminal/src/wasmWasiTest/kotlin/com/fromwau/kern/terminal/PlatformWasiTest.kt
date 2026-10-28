package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
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
    fun `a write the host refuses is refused with its errno`() {
        // WASI's EBADF: the host has no descriptor 99.
        val stream = WasiStream(99, Stream.Out)

        stream.write("lost\n")

        assertEquals(WriteError.Refused(Stream.Out, "WASI errno 8"), stream.failure)
    }

    @Test
    fun `a read the host refuses is Refused with its errno`() {
        // WASI's EBADF: the host has no descriptor 99.
        assertEquals(Err(ReadError.Refused("WASI errno 8")), readChunk(99, ByteArray(16)))
    }

    @Test
    fun `the environment is read from the host`() {
        assertNotNull(platformIo().env("PATH"))
    }
}
