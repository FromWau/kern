package com.fromwau.kern.terminal.test

import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.WriteError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Dispatchers.Default runs these on several threads on the JVM and native; elsewhere it shows the same contract.
class RecordingTerminalConcurrencyTest {

    @Test
    fun `writes from several threads are all kept in the order each thread made them`() = runTest {
        val terminal = RecordingTerminal()

        withContext(Dispatchers.Default) {
            repeat(WRITERS) { writer ->
                launch {
                    repeat(LINES) { line -> terminal.out("$writer:$line\n") }
                }
            }
        }

        val lines = terminal.out.lines().dropLast(1)
        assertEquals(WRITERS * LINES, lines.size)
        repeat(WRITERS) { writer ->
            val own = lines.filter { it.substringBefore(':') == "$writer" }
            assertEquals(List(LINES) { "$writer:$it" }, own)
        }
    }

    @Test
    fun `writes from several threads to both streams keep their stream in the order each thread made them`() = runTest {
        val terminal = RecordingTerminal()

        withContext(Dispatchers.Default) {
            repeat(WRITERS) { writer ->
                launch {
                    repeat(LINES) { line ->
                        if (line % 2 == 0) terminal.out("$writer:$line\n") else terminal.err("$writer:$line\n")
                    }
                }
            }
        }

        val writes = terminal.writes
        assertEquals(WRITERS * LINES, writes.size)
        repeat(WRITERS) { writer ->
            val own = writes.filter { it.text.substringBefore(':') == "$writer" }
            val expected = List(LINES) { line ->
                Write(if (line % 2 == 0) Stream.Out else Stream.Err, "$writer:$line\n")
            }
            assertEquals(expected, own)
        }
    }

    @Test
    fun `a stream failing while several threads write keeps every write in writes or lostWrites`() = runTest {
        val terminal = RecordingTerminal()

        withContext(Dispatchers.Default) {
            repeat(WRITERS) { writer ->
                launch {
                    repeat(LINES) { line ->
                        if (writer == 0 && line == LINES / 2) terminal.failWith(WriteError.BrokenPipe(Stream.Out))
                        if (line % 2 == 0) terminal.out("$writer:$line\n") else terminal.err("$writer:$line\n")
                    }
                }
            }
        }

        val lost = terminal.lostWrites
        assertTrue(lost.isNotEmpty())
        assertTrue(lost.all { it.stream == Stream.Out })
        val kept = terminal.writes
        repeat(WRITERS) { writer ->
            val own = (kept + lost).filter { it.text.substringBefore(':') == "$writer" }
            val expected = List(LINES) { line ->
                Write(if (line % 2 == 0) Stream.Out else Stream.Err, "$writer:$line\n")
            }
            assertEquals(expected.toSet(), own.toSet())
            assertEquals(LINES, own.size)
        }
    }
}

private const val WRITERS = 8
private const val LINES = 2_000
