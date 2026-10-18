package com.fromwau.kern.terminal

import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

// The platform terminal as the JVM, native and WASI build it: writes land when they return.
private fun platformTerminal(): Terminal = PlatformIo(
    writeOut = {},
    writeErr = {},
    isTty = false,
    width = null,
    ansiCapable = true,
    env = { null },
).toTerminal()

private fun bothTerminals(): List<Terminal> = listOf(RecordingTerminal(), platformTerminal())

private fun chain(terminal: Terminal, remaining: Int, finished: () -> Unit) {
    if (remaining == 0) {
        finished()
    } else {
        terminal.afterWrites { chain(terminal, remaining - 1, finished) }
    }
}

private const val DEPTH = 1_000_000

class AfterWritesNestingTest {

    @Test
    fun `a million chained calls finish on the default afterWrites`() {
        var finished = false
        chain(RecordingTerminal(), DEPTH) { finished = true }
        assertEquals(true, finished)
    }

    @Test
    fun `a million chained calls finish on the platform terminal`() {
        var finished = false
        chain(platformTerminal(), DEPTH) { finished = true }
        assertEquals(true, finished)
    }

    @Test
    fun `a call from inside a block runs once that block returns and the outermost call runs before it returns`() {
        for (terminal in bothTerminals()) {
            val events = mutableListOf<String>()

            terminal.afterWrites {
                events += "outer starts"
                terminal.afterWrites { events += "inner" }
                events += "outer ends"
            }
            events += "outermost call returned"

            assertEquals(listOf("outer starts", "outer ends", "inner", "outermost call returned"), events)
        }
    }

    @Test
    fun `calls from inside one block run in the order they were made`() {
        for (terminal in bothTerminals()) {
            val events = mutableListOf<String>()

            terminal.afterWrites {
                terminal.afterWrites {
                    events += "first"
                    terminal.afterWrites { events += "first's own" }
                }
                terminal.afterWrites { events += "second" }
            }

            assertEquals(listOf("first", "second", "first's own"), events)
        }
    }

    @Test
    fun `a throw reaches the outermost call and drops the blocks still waiting so the next call runs at once`() {
        for (terminal in bothTerminals()) {
            val events = mutableListOf<String>()

            val thrown = assertFailsWith<IllegalStateException> {
                terminal.afterWrites {
                    terminal.afterWrites { error("inner failed") }
                    terminal.afterWrites { events += "dropped" }
                }
            }
            terminal.afterWrites { events += "next call" }

            assertEquals("inner failed", thrown.message)
            assertEquals(listOf("next call"), events)
        }
    }
}
