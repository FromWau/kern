package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals

private class FailingStream : OutputStream() {
    override fun write(b: Int): Unit = throw IOException("No space left on device")
}

private class WorkingStream : OutputStream() {
    override fun write(b: Int) = Unit
}

private fun terminal(out: OutputStream, err: OutputStream) =
    jvmPlatformIo(isTty = false, outSink = out, errSink = err).toTerminal()

/** The JVM survives a failed write and keeps it, without a reason the JDK does not give. */
class JvmTerminalTest {

    @Test
    fun `writes that land are a success`() {
        val terminal = terminal(WorkingStream(), WorkingStream())
        terminal.out("fine")
        terminal.err("also fine")
        assertEquals(Ok(Unit), terminal.writeResult())
    }

    @Test
    fun `a failed write is Unknown with the platform's message`() {
        val terminal = terminal(FailingStream(), WorkingStream())
        terminal.out("doomed")
        assertEquals(Err(WriteError.Unknown("No space left on device")), terminal.writeResult())
    }

    @Test
    fun `a fresh terminal is not tainted by another terminal's failure`() {
        val broken = terminal(FailingStream(), WorkingStream())
        broken.out("doomed")
        assertEquals(Err(WriteError.Unknown("No space left on device")), broken.writeResult())

        val healthy = terminal(WorkingStream(), WorkingStream())
        healthy.out("fine")
        assertEquals(Ok(Unit), healthy.writeResult())
    }
}
