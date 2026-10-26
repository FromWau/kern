package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val ESC = Char(27)

// Handed a terminal rather than calling defaultTerminal(), so a test run prints nothing to the real console.
private fun writing(terminal: Terminal) {
    terminal.out("hello\n")
    terminal.err("something went wrong\n")
}

private fun colour(terminal: Terminal, path: String) {
    terminal.err(red("error: ", bold(path), " is missing\n"))
}

private fun terminalFor(colorOption: String): Terminal {
    val terminal = when (colorOption) {
        "always" -> defaultTerminal().withAnsi(out = true, err = true)
        "never" -> defaultTerminal().withAnsi(out = false, err = false)
        else -> defaultTerminal()
    }
    return terminal
}

private fun layoutWidth(terminal: Terminal): Int {
    val width = if (terminal.outIsTerminal) terminal.columns else 0 // 0: do not wrap
    return width
}

// The README's main, handing the exit code to [exit] instead of ending the test process.
private fun mainExitingWith(terminal: Terminal, exit: (Int) -> Unit) {
    terminal.out("done\n")
    terminal.afterWrites { written -> exit(exitCode(terminal, written)) }
}

fun exitCode(terminal: Terminal, written: EmptyResult<WriteError>): Int =
    when (written) {
        is Result.Success -> 0
        is Result.Error -> when (val error = written.error) {
            is WriteError.BrokenPipe -> BROKEN_PIPE_EXIT
            is WriteError.Refused -> cannotWrite(terminal, error.stream, error.detail)
            is WriteError.Unknown -> cannotWrite(terminal, error.stream, error.detail)
        }
    }

fun cannotWrite(terminal: Terminal, stream: Stream, detail: String?): Int {
    val name = if (stream == Stream.Out) "standard output" else "standard error"
    terminal.err("error: cannot write to $name: $detail\n")
    return 1
}

// The README's streaming main, with the lines and the exit code handed in.
private fun streamingMainExitingWith(terminal: Terminal, lines: Sequence<String>, exit: (Int) -> Unit) {
    terminal.outEach(lines) { written -> exit(exitCode(terminal, written)) }
}

private fun failingTerminal(): RecordingTerminal {
    val terminal = RecordingTerminal()
    terminal.failWith(WriteError.BrokenPipe(Stream.Out))
    terminal.failWith(WriteError.Refused(Stream.Err, "disk full"))
    return terminal
}

suspend fun finish(terminal: Terminal): Int = exitCode(terminal, terminal.awaitWriteResult())

class Recorder : Terminal {
    val written = StringBuilder()
    override fun out(text: String) { written.append(text) }
    override fun err(text: String) = Unit
}

// The README's snippets, kept here so they compile against the real API and do what the README says.
class ReadmeSnippetsTest {

    @Test
    fun `writing sends each line to its own stream`() {
        val terminal = RecordingTerminal()
        writing(terminal)
        assertEquals("hello\n", terminal.out)
        assertEquals("something went wrong\n", terminal.err)
    }

    @Test
    fun `the colour line is styled for standard error`() {
        val terminal = RecordingTerminal(errAnsi = true)
        colour(terminal, "notes.txt")
        assertEquals("$ESC[31merror: $ESC[1mnotes.txt$ESC[0m$ESC[31m is missing$ESC[0m\n", terminal.err)
    }

    @Test
    fun `the styling examples do what the README says`() {
        val joined: Styled = red("a") + " b"
        assertEquals("a b", joined.toString())
        assertEquals(red("a") + " b", plain(red("a"), " b"))
        val list: Styled = listOf(red("a"), "b").joinToStyled(dim(" | "))
        assertEquals("$ESC[31ma$ESC[0m$ESC[2m | $ESC[0mb", list.render(ansi = true))
        assertEquals("a | b", listOf(red("a"), "b").joinToString(" | "))
        assertEquals("a $ESC[31mb$ESC[0m", plain("a ", red("b")).render(ansi = true))
        assertEquals(12, red("name").padEnd(12).length)
        assertEquals("$ESC[1;31mfailed$ESC[0m", (bold + red)("failed").render(ansi = true))
        assertEquals("a\n  ", red("a\n").padEnd(4).toString())
    }

    @Test
    fun `a colour option overrides both streams`() {
        assertTrue(terminalFor("always").ansi)
        assertTrue(terminalFor("always").errAnsi)
        assertFalse(terminalFor("never").ansi)
        assertFalse(terminalFor("never").errAnsi)
    }

    @Test
    fun `the policy functions take a lookup of your own`() {
        val myEnv = mapOf("COLUMNS" to "100")
        assertEquals(100, resolveColumns(myEnv::get))
        assertTrue(ansiEnabled(isTty = true, env = myEnv::get))
    }

    @Test
    fun `layout wraps only for a terminal`() {
        assertEquals(100, layoutWidth(RecordingTerminal(columns = 100, outIsTerminal = true)))
        assertEquals(0, layoutWidth(RecordingTerminal(columns = 100)))
    }

    @Test
    fun `main exits zero after writes that landed`() {
        val terminal = RecordingTerminal()
        var exited: Int? = null

        mainExitingWith(terminal) { exited = it }

        assertEquals("done\n", terminal.out)
        assertEquals(0, exited)
    }

    @Test
    fun `main exits with the broken pipe code for a closed pipe`() {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        var exited: Int? = null

        mainExitingWith(terminal) { exited = it }

        assertEquals(BROKEN_PIPE_EXIT, exited)
    }

    @Test
    fun `finish exits zero after writes that landed`() = runTest {
        assertEquals(0, finish(RecordingTerminal()))
    }

    @Test
    fun `finish exits with the broken pipe code for a closed pipe`() = runTest {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.BrokenPipe(Stream.Out))
        assertEquals(BROKEN_PIPE_EXIT, finish(terminal))
    }

    @Test
    fun `finish reports a failure it cannot name and exits one`() = runTest {
        val terminal = RecordingTerminal()
        terminal.failWith(WriteError.Unknown(Stream.Out, "No space left on device"))

        assertEquals(1, finish(terminal))
        assertEquals("error: cannot write to standard output: No space left on device\n", terminal.err)
    }

    @Test
    fun `printing a long output writes every line and exits zero`() {
        val terminal = RecordingTerminal()
        var exited: Int? = null

        streamingMainExitingWith(terminal, (1..10_000).asSequence().map { "line $it\n" }) { exited = it }

        assertEquals(10_000, terminal.out.count { it == '\n' })
        assertEquals(0, exited)
    }

    @Test
    fun `printing a long output stops after the write that failed`() {
        val terminal = RecordingTerminal()
        var taken = 0
        val lines = (1..10_000_000).asSequence().map { number ->
            taken = number
            if (number == 5_000) terminal.failWith(WriteError.BrokenPipe(Stream.Out))
            "line $number\n"
        }
        var exited: Int? = null

        streamingMainExitingWith(terminal, lines) { exited = it }

        assertEquals(BROKEN_PIPE_EXIT, exited)
        // The first write's lines 1 to 6,664 fill a chunk of 64 K characters, and line 6,665 did not fit in it.
        assertEquals(6_665, taken)
    }

    @Test
    fun `the errors a test fails a terminal with are reported per stream`() {
        val terminal = failingTerminal()

        assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.writeResult(Stream.Out))
        assertEquals(Err(WriteError.Refused(Stream.Err, "disk full")), terminal.writeResult(Stream.Err))
        assertEquals(null, WriteError.Unknown(Stream.Out, "No space left on device").cause)
    }

    @Test
    fun `a hand written terminal takes the safe defaults`() = runTest {
        val recorder = Recorder()
        recorder.out("x")

        assertEquals("x", recorder.written.toString())
        assertEquals(0, recorder.columns)
        assertFalse(recorder.ansi || recorder.errAnsi || recorder.outIsTerminal || recorder.errIsTerminal)
        assertEquals(0, finish(recorder))
    }
}
