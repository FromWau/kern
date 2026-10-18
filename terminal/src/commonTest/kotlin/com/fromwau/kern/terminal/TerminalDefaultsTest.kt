package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.Test
import kotlin.test.assertEquals

class TerminalDefaultsTest {

    @Test
    fun `the block after writes runs at once with the write result`() {
        val terminal = RecordingTerminal()
        val answers = mutableListOf<EmptyResult<WriteError>>()

        terminal.afterWrites { answers += it }
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        terminal.afterWrites { answers += it }

        assertEquals(listOf<EmptyResult<WriteError>>(Ok(Unit), Err(WriteError.BrokenPipe(Stream.Out))), answers)
    }
}
