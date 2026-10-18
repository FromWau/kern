package com.fromwau.kern.terminal

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private val ESC = Char(27)

private fun rendered(text: Styled): String = RecordingTerminal(ansi = true).also { it.out(text) }.out

class StyledTest {

    @Test
    fun `a styled text is written in its codes and a reset`() {
        assertEquals("$ESC[1;31mfailed$ESC[0m", rendered((bold + red)("failed")))
    }

    @Test
    fun `a stream that takes no colour gets the plain text`() {
        val terminal = RecordingTerminal(ansi = false)
        terminal.out(red("failed"))
        assertEquals("failed", terminal.out)
    }

    @Test
    fun `each stream is written with its own colour decision`() {
        val terminal = RecordingTerminal(ansi = false, errAnsi = true)

        terminal.out(red("out"))
        terminal.err(red("err"))

        assertEquals("out", terminal.out)
        assertEquals("$ESC[31merr$ESC[0m", terminal.err)
    }

    @Test
    fun `text after a nested piece is written in the outer style again`() {
        assertEquals(
            "$ESC[31ma $ESC[1mb$ESC[0m$ESC[31m c$ESC[0m",
            rendered(red("a ", bold("b"), " c")),
        )
    }

    @Test
    fun `text after a piece nested two deep gets every enclosing style back`() {
        assertEquals(
            "$ESC[31m$ESC[1m$ESC[4mx$ESC[0m$ESC[31m$ESC[1my$ESC[0m",
            rendered(red(bold(underline("x"), "y"))),
        )
    }

    @Test
    fun `the reset goes before a trailing newline`() {
        assertEquals("$ESC[31ma$ESC[0m\n", rendered(red("a\n")))
        assertEquals("$ESC[31ma$ESC[0m\r\n", rendered(red("a\r\n")))
    }

    @Test
    fun `each line is closed on its own`() {
        assertEquals(
            "$ESC[31mline 1$ESC[0m\n$ESC[31mline 2$ESC[0m\n",
            rendered(red("line 1\nline 2\n")),
        )
        assertEquals("$ESC[31ma$ESC[0m\r\n$ESC[31mb$ESC[0m", rendered(red("a\r\nb")))
        assertEquals("$ESC[31ma$ESC[0m\n\n$ESC[31mb$ESC[0m", rendered(red("a\n\nb")))
    }

    @Test
    fun `a line in a nested piece reopens every style in effect`() {
        assertEquals(
            "$ESC[31ma$ESC[0m\n$ESC[31m$ESC[1mb$ESC[0m\n$ESC[31m$ESC[1mc$ESC[0m$ESC[31md$ESC[0m",
            rendered(red("a\n", bold("b\nc"), "d")),
        )
    }

    @Test
    fun `line breaks before any text open nothing`() {
        assertEquals("\n$ESC[31ma$ESC[0m", rendered(red("\na")))
    }

    @Test
    fun `a piece of line breaks alone is written without codes`() {
        assertEquals("\n", rendered(red("\n")))
        assertEquals("\r\n", rendered(red(bold("\r\n"))))
        assertEquals("$ESC[31ma$ESC[0m\n", rendered(red("a") + red("\n")))
    }

    @Test
    fun `line breaks after a nested piece do not reopen the outer style`() {
        assertEquals("$ESC[31ma$ESC[1mb$ESC[0m\n", rendered(red("a", bold("b"), "\n")))
    }

    @Test
    fun `line breaks in a nested piece close the line`() {
        assertEquals("$ESC[31ma$ESC[0m\n$ESC[31mb$ESC[0m", rendered(red("a", bold("\n"), "b")))
        assertEquals("$ESC[31ma$ESC[0m\n\n", rendered(red("a\n", bold("\n"))))
    }

    @Test
    fun `two colours in one style write both codes in number order`() {
        assertEquals("$ESC[31;32mx$ESC[0m", rendered((red + green)("x")))
        assertEquals("$ESC[31;32mx$ESC[0m", rendered((green + red)("x")))
    }

    @Test
    fun `an empty piece writes nothing`() {
        assertEquals("", rendered(red("")))
        assertEquals("$ESC[31mab$ESC[0m", rendered(red("a", bold(""), "b")))
    }

    @Test
    fun `plain text is written without codes`() {
        assertEquals("x", rendered(plain("x")))
        assertEquals("a$ESC[31mb$ESC[0mc", rendered(plain("a", red("b"), "c")))
    }

    @Test
    fun `escape codes inside a string pass through unchanged`() {
        val raw = "$ESC[32mgreen$ESC[0m"
        assertEquals("$ESC[31m$raw$ESC[0m", rendered(red(raw)))
    }

    @Test
    fun `joined pieces keep their own styles`() {
        assertEquals(
            "$ESC[31ma$ESC[0m b$ESC[1mc$ESC[0m",
            rendered(red("a") + " b" + bold("c")),
        )
    }

    @Test
    fun `a string template gets the plain text`() {
        val styled = red("a ", bold("b"))
        assertEquals("[a b]", "[$styled]")
        assertEquals("a b", styled.toString())
    }

    @Test
    fun `a string on the left of plus makes plain text`() {
        val joined: String = "a " + red("b")
        assertEquals("a b", joined)
    }

    @Test
    fun `length counts the visible text`() {
        assertEquals(3, red("a", bold("b"), "c").length)
    }

    @Test
    fun `padding counts the visible text and stays styled`() {
        val padded: Styled = red("ab").padEnd(4)
        assertEquals("ab  ", padded.toString())
        assertEquals("$ESC[31mab$ESC[0m  ", rendered(padded))
        assertEquals("  $ESC[31mab$ESC[0m", rendered(red("ab").padStart(4)))
        assertEquals(red("abc"), red("abc").padEnd(2))
    }

    @Test
    fun `styled texts are equal when they are built the same way`() {
        assertEquals(red("a", bold("b")), red("a", bold("b")))
        assertEquals(red("a", bold("b")).hashCode(), red("a", bold("b")).hashCode())
        assertEquals(red("ab"), red("a", "b"))
        assertEquals(red("a") + "b", plain(red("a"), "b"))
        assertEquals(red("a"), plain(red("a")))
        assertNotEquals(red("ab"), bold("ab"))
        assertNotEquals(red("a", bold("b")), red("ab"))
        assertNotEquals(red("a"), red(red("a")))
        assertNotEquals(red("ab"), red("a") + red("b"))
    }

    @Test
    fun `a disabled terminal colour override writes plain text`() {
        val colours = RecordingTerminal(ansi = true, errAnsi = true)
        val never = colours.withAnsi(out = false, err = false)

        never.out(red("a"))
        never.err(red("b"))

        assertEquals("a", colours.out)
        assertEquals("b", colours.err)
    }

    @Test
    fun `an enabled colour override colours a terminal that takes none`() {
        val plainOnly = RecordingTerminal()
        val always = plainOnly.withAnsi(out = true, err = false)

        always.out(red("a"))
        always.err(red("b"))

        assertEquals("$ESC[31ma$ESC[0m", plainOnly.out)
        assertEquals("b", plainOnly.err)
    }

    @Test
    fun `a colour override answers everything else from the terminal it wraps`() = runTest {
        val wrapped = object : Terminal {
            override fun out(text: String) = Unit
            override fun err(text: String) = Unit

            override val columns = 72
            override val outIsTerminal = true
            override val errIsTerminal = true

            override fun writeResult(): EmptyResult<WriteError> =
                Err(WriteError.Refused(Stream.Out, "from writeResult()"))

            override fun writeResult(stream: Stream): EmptyResult<WriteError> =
                Err(WriteError.Unknown(stream, "from writeResult(stream)"))

            override suspend fun awaitWriteResult(): EmptyResult<WriteError> = Err(WriteError.BrokenPipe(Stream.Err))

            override fun afterWrites(block: (EmptyResult<WriteError>) -> Unit) =
                block(Err(WriteError.Refused(Stream.Err, "from afterWrites")))
        }
        val terminal = wrapped.withAnsi(out = true, err = true)

        assertEquals(72, terminal.columns)
        assertEquals(true, terminal.ansi)
        assertEquals(true, terminal.errAnsi)
        assertEquals(true, terminal.outIsTerminal)
        assertEquals(true, terminal.errIsTerminal)
        assertEquals(Err(WriteError.Refused(Stream.Out, "from writeResult()")), terminal.writeResult())
        assertEquals(Err(WriteError.Unknown(Stream.Out, "from writeResult(stream)")), terminal.writeResult(Stream.Out))
        assertEquals(Err(WriteError.BrokenPipe(Stream.Err)), terminal.awaitWriteResult())
        var afterWrites: EmptyResult<WriteError>? = null
        terminal.afterWrites { afterWrites = it }
        assertEquals(Err(WriteError.Refused(Stream.Err, "from afterWrites")), afterWrites)
    }
}
