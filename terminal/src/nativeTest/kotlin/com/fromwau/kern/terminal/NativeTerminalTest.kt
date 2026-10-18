package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeTerminalTest {

    @Test
    fun `awaiting the write result answers what writeResult answers`() = runTest {
        val terminal = defaultTerminal()
        terminal.out("kern terminal test: standard output\n")
        assertEquals(Ok(Unit), terminal.awaitWriteResult())
        assertEquals(terminal.writeResult(), terminal.awaitWriteResult())
    }

    @Test
    fun `the block after writes runs at once with the write result`() {
        val terminal = defaultTerminal()
        terminal.out("kern terminal test: standard output\n")
        var answer: EmptyResult<WriteError>? = null

        terminal.afterWrites { answer = it }

        assertEquals(terminal.writeResult(), answer)
    }
}
