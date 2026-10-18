package com.fromwau.kern.terminal.coroutines

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

private suspend fun writeLines(terminal: Terminal, lines: Flow<String>): EmptyResult<WriteError> {
    val written = terminal.outEach(lines.map { "$it\n" })   // EmptyResult<WriteError>
    return written
}

// The README's snippet, kept here so it compiles against the real API and does what the README says.
class ReadmeSnippetsTest {

    @Test
    fun `the snippet writes each line with a newline`() = runTest {
        val terminal = RecordingTerminal()

        val written = writeLines(terminal, flowOf("one", "two"))

        assertEquals("one\ntwo\n", terminal.out)
        assertEquals(Ok(Unit), written)
    }
}
