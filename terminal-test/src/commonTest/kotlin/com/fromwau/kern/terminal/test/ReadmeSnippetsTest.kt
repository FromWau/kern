package com.fromwau.kern.terminal.test

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.BROKEN_PIPE_EXIT
import com.fromwau.kern.terminal.Stream
import com.fromwau.kern.terminal.Terminal
import com.fromwau.kern.terminal.WriteError
import com.fromwau.kern.terminal.err
import com.fromwau.kern.terminal.red
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Not in the README: the code under test.
private fun report(terminal: Terminal, missing: String) {
    terminal.err(red("$missing is missing\n"))
}

// Not in this README: the failure handling from kern:terminal's README, which the second snippet calls.
private suspend fun finish(terminal: Terminal): Int = exitCode(terminal, terminal.awaitWriteResult())

private fun exitCode(terminal: Terminal, written: EmptyResult<WriteError>): Int =
    when (written) {
        is Result.Success -> 0
        is Result.Error -> when (val error = written.error) {
            is WriteError.BrokenPipe -> BROKEN_PIPE_EXIT
            is WriteError.Refused -> cannotWrite(terminal, error.stream, error.detail)
            is WriteError.Unknown -> cannotWrite(terminal, error.stream, error.detail)
        }
    }

private fun cannotWrite(terminal: Terminal, stream: Stream, detail: String?): Int {
    val name = if (stream == Stream.Out) "standard output" else "standard error"
    terminal.err("error: cannot write to $name: $detail\n")
    return 1
}

// The README's snippets, kept here so they compile against the real API and do what the README says.
class ReadmeSnippetsTest {

    @Test
    fun `a missing file is reported in red`() {
        val terminal = RecordingTerminal(errAnsi = true)

        report(terminal, missing = "notes.txt")

        assertEquals("", terminal.out)
        assertEquals("${Char(27)}[31mnotes.txt is missing${Char(27)}[0m\n", terminal.err)
    }

    @Test
    fun `a closed pipe exits with the broken pipe code`() = runTest {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))

        terminal.out("lost\n")

        assertEquals(BROKEN_PIPE_EXIT, finish(terminal))
        assertEquals("", terminal.out)
    }
}
