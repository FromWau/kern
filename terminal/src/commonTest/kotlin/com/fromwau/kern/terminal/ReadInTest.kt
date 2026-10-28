package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A standard input that hands out [chunks] one read at a time, then its end. */
private fun chunked(vararg chunks: ByteArray): (ByteArray) -> Result<Int, ReadError> {
    val remaining = ArrayDeque(chunks.toList())
    return { buffer ->
        val chunk = remaining.removeFirstOrNull() ?: ByteArray(0)
        val count = minOf(chunk.size, buffer.size)
        chunk.copyInto(buffer, endIndex = count)
        if (count < chunk.size) remaining.addFirst(chunk.copyOfRange(count, chunk.size))
        Ok(count)
    }
}

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val crlf = bytes(0x61, 0x0D, 0x0A, 0x62, 0x0D, 0x0A)
private val bom = bytes(0xEF, 0xBB, 0xBF, 0x63)
private val invalidUtf8 = bytes(0xC3, 0x28, 0xFF, 0x00)

class ReadInTest {

    @Test
    fun `every byte comes through exactly as written`() {
        val read = readAll(maxBytes = null, chunked(crlf, bom, invalidUtf8)).assertSuccess()

        assertContentEquals(crlf + bom + invalidUtf8, read)
    }

    @Test
    fun `an input longer than one read is read to its end`() {
        val chunk = ByteArray(64 * 1024) { it.toByte() }

        val read = readAll(maxBytes = null, chunked(chunk, chunk, crlf)).assertSuccess()

        assertContentEquals(chunk + chunk + crlf, read)
    }

    @Test
    fun `an empty input reads as no bytes`() {
        assertContentEquals(ByteArray(0), readAll(maxBytes = null, chunked()).assertSuccess())
    }

    @Test
    fun `an input of exactly maxBytes is read whole`() {
        assertContentEquals(crlf, readAll(maxBytes = crlf.size.toLong(), chunked(crlf)).assertSuccess())
    }

    @Test
    fun `an input past maxBytes is TooLarge one byte past the limit`() {
        val error = readAll(maxBytes = 4, chunked(crlf, bom)).assertError<ReadError.TooLarge>()

        assertEquals(ReadError.TooLarge(limitBytes = 4, atLeastBytes = 5), error)
    }

    @Test
    fun `a read past maxBytes asks for no more than one byte past the limit`() {
        val asked = mutableListOf<Int>()
        val endless: (ByteArray) -> Result<Int, ReadError> = { buffer -> asked += buffer.size; Ok(buffer.size) }

        readAll(maxBytes = 70_000, endless).assertError<ReadError.TooLarge>()
        assertEquals(70_001, asked.sum())
    }

    @Test
    fun `a negative maxBytes is TooLarge before anything is read`() {
        var asked = false
        val read = readAll(maxBytes = -1) {
            asked = true
            Ok(0)
        }

        assertEquals(Err(ReadError.TooLarge(limitBytes = -1, atLeastBytes = 0)), read)
        assertFalse(asked)
    }

    @Test
    fun `a failed read reports its error`() {
        readAll(maxBytes = null) { Err(ReadError.IsADirectory) }.assertError<ReadError.IsADirectory>()
    }

    @Test
    fun `a terminal of your own reads an empty standard input that is no terminal`() {
        val terminal = object : Terminal {
            override fun out(text: String) = Unit

            override fun err(text: String) = Unit
        }

        assertContentEquals(ByteArray(0), terminal.readIn().assertSuccess())
        assertFalse(terminal.inIsTerminal)
    }

    @Test
    fun `the colour override reads the standard input of the terminal it wraps`() {
        val wrapped = RecordingTerminal(inIsTerminal = true, input = crlf).withAnsi(out = true, err = true)

        assertTrue(wrapped.inIsTerminal)
        assertContentEquals(crlf, wrapped.readIn().assertSuccess())
    }
}
