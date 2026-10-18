package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals

private val ESC = Char(27)

/** 16 characters, so 4,096 of them fill a chunk. */
private fun piece(number: Int): String = "piece ${number.toString().padStart(9, '0')}\n"

/** Keeps each `out` call apart, so a test can see the chunks. */
private class ChunkRecorder(val recording: RecordingTerminal = RecordingTerminal()) : Terminal by recording {
    val chunks = mutableListOf<String>()

    override fun out(text: String) {
        chunks += text
        recording.out(text)
    }
}

/** What [outEach] passed to `finished`, one entry per call. */
private fun Terminal.outEachFinishing(pieces: Sequence<CharSequence>): List<EmptyResult<WriteError>> {
    val finished = mutableListOf<EmptyResult<WriteError>>()
    outEach(pieces) { finished += it }
    return finished
}

class OutEachTest {

    @Test
    fun `every piece is written in order as given`() {
        val terminal = RecordingTerminal()
        val pieces = (1..10_000).map { "line $it\n" }

        val finished = terminal.outEachFinishing(pieces.asSequence())

        assertEquals(pieces.joinToString(""), terminal.out)
        assertEquals(listOf<EmptyResult<WriteError>>(Ok(Unit)), finished)
    }

    @Test
    fun `small pieces are joined into chunks of 64 K characters`() {
        val terminal = ChunkRecorder()

        val _ = terminal.outEachFinishing((1..10_000).asSequence().map(::piece))

        assertEquals(listOf(65_536, 65_536, 28_928), terminal.chunks.map { it.length })
    }

    @Test
    fun `a piece longer than a chunk goes out alone and whole`() {
        val terminal = ChunkRecorder()
        val long = "x".repeat(70_000)

        val _ = terminal.outEachFinishing(sequenceOf("a", long, "b"))

        assertEquals(listOf("a", long, "b"), terminal.chunks)
    }

    @Test
    fun `styled pieces are coloured when standard output takes colour`() {
        val terminal = RecordingTerminal(ansi = true)

        val _ = terminal.outEachFinishing(sequenceOf(red("a"), "b\n"))

        assertEquals("$ESC[31ma$ESC[0mb\n", terminal.out)
    }

    @Test
    fun `styled pieces are plain when standard output takes no colour`() {
        val terminal = RecordingTerminal(ansi = false, errAnsi = true)

        val _ = terminal.outEachFinishing(sequenceOf(red("a"), "b\n"))

        assertEquals("ab\n", terminal.out)
    }

    @Test
    fun `writing stops at the first failed chunk and reports its failure once`() {
        val terminal = ChunkRecorder()
        var pulled = 0
        val pieces = (1..20_000).asSequence().map { number ->
            pulled = number
            if (number == 100) terminal.recording.failWith(WriteError.BrokenPipe(Stream.Out))
            piece(number)
        }

        val finished = terminal.outEachFinishing(pieces)

        assertEquals(listOf<EmptyResult<WriteError>>(Err(WriteError.BrokenPipe(Stream.Out))), finished)
        // The first chunk's 4,096 pieces, and the one that did not fit in it.
        assertEquals(4_097, pulled)
        assertEquals(1, terminal.chunks.size)
    }

    @Test
    fun `a failure of the last write is reported`() {
        val terminal = RecordingTerminal()
        val pieces = sequenceOf("a", "b").map { piece ->
            if (piece == "b") terminal.failWith(WriteError.BrokenPipe(Stream.Out))
            piece
        }

        val finished = terminal.outEachFinishing(pieces)

        assertEquals(listOf<EmptyResult<WriteError>>(Err(WriteError.BrokenPipe(Stream.Out))), finished)
    }

    @Test
    fun `a failed standard error does not stop the output and is reported at the end`() {
        val terminal = RecordingTerminal()
        val pieces = (1..10_000).asSequence().map { number ->
            if (number == 100) terminal.failWith(WriteError.Refused(Stream.Err, "closed"))
            piece(number)
        }

        val finished = terminal.outEachFinishing(pieces)

        assertEquals(10_000 * 16, terminal.out.length)
        assertEquals(listOf<EmptyResult<WriteError>>(Err(WriteError.Refused(Stream.Err, "closed"))), finished)
    }

    @Test
    fun `an empty sequence writes nothing and finishes once with success`() {
        val terminal = ChunkRecorder()

        val finished = terminal.outEachFinishing(emptySequence())

        assertEquals(emptyList(), terminal.chunks)
        assertEquals(listOf<EmptyResult<WriteError>>(Ok(Unit)), finished)
    }

    @Test
    fun `a million chunks are written without a stack overflow`() {
        val chunkLong = "x".repeat(65_536)
        var chunks = 0
        val terminal = object : Terminal {
            override fun out(text: String) {
                chunks++
            }

            override fun err(text: String) = Unit
        }

        val finished = terminal.outEachFinishing(generateSequence { chunkLong }.take(1_000_000))

        assertEquals(1_000_000, chunks)
        assertEquals(listOf<EmptyResult<WriteError>>(Ok(Unit)), finished)
    }
}
