package com.fromwau.kern.terminal.test

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

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
}

private const val WRITERS = 8
private const val LINES = 2_000
