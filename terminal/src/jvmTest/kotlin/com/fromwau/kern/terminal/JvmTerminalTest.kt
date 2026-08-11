package com.fromwau.kern.terminal

import java.io.IOException
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FailingStream : OutputStream() {
    override fun write(b: Int): Unit = throw IOException("broken pipe")
}

private class WorkingStream : OutputStream() {
    override fun write(b: Int) = Unit
}

private fun terminal(out: OutputStream, err: OutputStream) =
    jvmPlatformIo(isTty = false, outSink = out, errSink = err).toTerminal()

/**
 * The JVM-only half of broken-pipe handling: unlike POSIX native, the JVM survives a closed pipe and
 * reports it through PrintStream's latched error flag, which a caller maps to [BROKEN_PIPE_EXIT].
 */
class JvmTerminalTest {

    @Test
    fun `writeErrored is false when nothing failed`() {
        val terminal = terminal(WorkingStream(), WorkingStream())
        terminal.out("fine")
        terminal.err("also fine")
        assertFalse(terminal.writeErrored())
    }

    @Test
    fun `writeErrored detects a failed write on this terminal`() {
        val terminal = terminal(FailingStream(), WorkingStream())
        terminal.out("doomed")
        assertTrue(terminal.writeErrored())
    }

    @Test
    fun `a fresh terminal is not tainted by another terminals failure`() {
        // checkError() never resets once true, so two Terminals sharing one process-wide stream cannot
        // tell a fresh failure from an older run's. Each construction gets its own sink; a failure in one
        // must not decide the other's writeErrored().
        val broken = terminal(FailingStream(), WorkingStream())
        broken.out("doomed")
        assertTrue(broken.writeErrored())

        val healthy = terminal(WorkingStream(), WorkingStream())
        healthy.out("fine")
        assertFalse(healthy.writeErrored())
    }
}
