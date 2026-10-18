package com.fromwau.kern.terminal.coroutines

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.red
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

class OutEachTest {

    @Test
    fun `every piece is written in order as given`() = runTest {
        val terminal = RecordingTerminal()
        val pieces = (1..10_000).map { "line $it\n" }

        val written = terminal.outEach(pieces.asFlow())

        assertEquals(pieces.joinToString(""), terminal.out)
        assertEquals(Ok(Unit), written)
    }

    @Test
    fun `small pieces are joined into chunks of 64 K characters`() = runTest {
        val terminal = ChunkRecorder()

        val _ = terminal.outEach((1..10_000).asFlow().map(::piece))

        assertEquals(listOf(65_536, 65_536, 28_928), terminal.chunks.map { it.length })
    }

    @Test
    fun `a piece longer than a chunk goes out alone and whole`() = runTest {
        val terminal = ChunkRecorder()
        val long = "x".repeat(70_000)

        val _ = terminal.outEach(flowOf("a", long, "b"))

        assertEquals(listOf("a", long, "b"), terminal.chunks)
    }

    @Test
    fun `styled pieces are coloured when standard output takes colour`() = runTest {
        val terminal = RecordingTerminal(ansi = true)

        val _ = terminal.outEach(flowOf(red("a"), "b\n"))

        assertEquals("$ESC[31ma$ESC[0mb\n", terminal.out)
    }

    @Test
    fun `styled pieces are plain when standard output takes no colour`() = runTest {
        val terminal = RecordingTerminal(ansi = false, errAnsi = true)

        val _ = terminal.outEach(flowOf(red("a"), "b\n"))

        assertEquals("ab\n", terminal.out)
    }

    @Test
    fun `writing stops collecting at the first failed chunk and returns its failure`() = runTest {
        val terminal = ChunkRecorder()
        var emitted = 0
        val pieces = flow {
            for (number in 1..20_000) {
                emitted = number
                if (number == 100) terminal.recording.failWith(WriteError.BrokenPipe(Stream.Out))
                emit(piece(number))
            }
        }

        val written = terminal.outEach(pieces)

        assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), written)
        // The first chunk's 4,096 pieces, and the one that did not fit in it.
        assertEquals(4_097, emitted)
        assertEquals(1, terminal.chunks.size)
    }

    @Test
    fun `a failure of the last write is reported`() = runTest {
        val terminal = RecordingTerminal()
        val pieces = flow {
            emit("a")
            terminal.failWith(WriteError.BrokenPipe(Stream.Out))
            emit("b")
        }

        val written = terminal.outEach(pieces)

        assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), written)
    }

    @Test
    fun `a failed standard error does not stop the output and is reported at the end`() = runTest {
        val terminal = RecordingTerminal()
        val pieces = flow {
            for (number in 1..10_000) {
                if (number == 100) terminal.failWith(WriteError.Refused(Stream.Err, "closed"))
                emit(piece(number))
            }
        }

        val written = terminal.outEach(pieces)

        assertEquals(10_000 * 16, terminal.out.length)
        assertEquals(Err(WriteError.Refused(Stream.Err, "closed")), written)
    }

    @Test
    fun `an empty flow writes nothing and succeeds`() = runTest {
        val terminal = ChunkRecorder()

        val written = terminal.outEach(emptyFlow())

        assertEquals(emptyList(), terminal.chunks)
        assertEquals(Ok(Unit), written)
    }

    @Test
    fun `cancelling the caller cancels the writing`() = runTest {
        val terminal = RecordingTerminal()
        val waiting = CompletableDeferred<Unit>()
        var written: EmptyResult<WriteError>? = null
        val writing = launch {
            written = terminal.outEach(
                flow {
                    emit("a")
                    waiting.complete(Unit)
                    awaitCancellation()
                },
            )
        }
        waiting.await()

        writing.cancel()
        writing.join()

        assertTrue(writing.isCancelled)
        assertNull(written)
    }
}
