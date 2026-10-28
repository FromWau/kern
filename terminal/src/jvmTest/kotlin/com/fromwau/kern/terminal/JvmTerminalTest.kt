package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FailingStream : OutputStream() {
    val thrown = IOException("No space left on device")

    override fun write(b: Int): Unit = throw thrown
}

private class WorkingStream : OutputStream() {
    override fun write(b: Int) = Unit
}

private class FailingInput : InputStream() {
    val thrown = IOException("Is a directory")

    override fun read(): Int = throw thrown
}

private fun terminal(out: OutputStream, err: OutputStream) =
    jvmPlatformIo(isTty = false, outSink = out, errSink = err).toTerminal()

private fun reading(input: InputStream, isTty: Boolean = false) =
    jvmPlatformIo(isTty = isTty, outSink = WorkingStream(), errSink = WorkingStream(), inSource = input).toTerminal()

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
    fun `a failed write is Unknown with the platform's message and exception`() {
        val sink = FailingStream()
        val terminal = terminal(sink, WorkingStream())
        terminal.out("doomed")
        assertEquals(
            Err(WriteError.Unknown(Stream.Out, "No space left on device", sink.thrown)),
            terminal.writeResult(),
        )
    }

    @Test
    fun `a fresh terminal is not tainted by another terminal's failure`() {
        val sink = FailingStream()
        val broken = terminal(sink, WorkingStream())
        broken.out("doomed")
        assertEquals(
            Err(WriteError.Unknown(Stream.Out, "No space left on device", sink.thrown)),
            broken.writeResult(),
        )

        val healthy = terminal(WorkingStream(), WorkingStream())
        healthy.out("fine")
        assertEquals(Ok(Unit), healthy.writeResult())
    }

    @Test
    fun `a failed write to standard error names standard error`() {
        val sink = FailingStream()
        val terminal = terminal(WorkingStream(), sink)
        terminal.out("fine")
        terminal.err("doomed")
        assertEquals(
            Err(WriteError.Unknown(Stream.Err, "No space left on device", sink.thrown)),
            terminal.writeResult(),
        )
    }

    @Test
    fun `standard output's failure comes before standard error's`() {
        val terminal = terminal(FailingStream(), FailingStream())
        terminal.err("doomed first")
        terminal.out("doomed")
        assertEquals(Stream.Out, (terminal.writeResult() as Err).error.stream)
    }

    @Test
    fun `awaiting the write result answers what writeResult answers`() = runTest {
        val sink = FailingStream()
        val terminal = terminal(sink, WorkingStream())
        terminal.out("doomed")
        assertEquals(
            Err(WriteError.Unknown(Stream.Out, "No space left on device", sink.thrown)),
            terminal.awaitWriteResult(),
        )
        assertEquals(terminal.writeResult(), terminal.awaitWriteResult())
    }

    @Test
    fun `the block after writes runs at once with the write result`() {
        val terminal = terminal(FailingStream(), WorkingStream())
        terminal.out("doomed")
        var answer: EmptyResult<WriteError>? = null

        terminal.afterWrites { answer = it }

        assertEquals(terminal.writeResult(), answer)
    }

    @Test
    fun `standard input comes through byte for byte`() {
        val bytes = byteArrayOf(
            0xEF.toByte(),
            0xBB.toByte(),
            0xBF.toByte(),
            'a'.code.toByte(),
            0x0D,
            0x0A,
            0xC3.toByte(),
        )

        assertContentEquals(bytes, reading(ByteArrayInputStream(bytes)).readIn().assertSuccess())
    }

    @Test
    fun `a failed read of standard input is Unknown with the platform's message and exception`() {
        val input = FailingInput()

        assertEquals(
            Err(ReadError.Unknown("Is a directory", input.thrown)),
            reading(input).readIn(),
        )
    }

    @Test
    fun `standard input is a terminal when the one probe says so`() {
        assertTrue(reading(ByteArrayInputStream(ByteArray(0)), isTty = true).inIsTerminal)
        assertFalse(reading(ByteArrayInputStream(ByteArray(0)), isTty = false).inIsTerminal)
    }
}
