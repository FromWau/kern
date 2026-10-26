package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

private val ESC = Char(27)

class JoinToStyledTest {

    private val pieces: List<CharSequence> = listOf(red("a"), bold("b"), "c")

    @Test
    fun `each piece keeps its own style around a plain separator`() {
        val joined = pieces.joinToStyled()

        assertEquals("$ESC[31ma$ESC[0m, $ESC[1mb$ESC[0m, c", joined.render(ansi = true))
        assertEquals("a, b, c", joined.render(ansi = false))
    }

    @Test
    fun `a styled separator keeps its style between the pieces`() {
        val joined = pieces.joinToStyled(separator = dim("|"))

        assertEquals(
            "$ESC[31ma$ESC[0m$ESC[2m|$ESC[0m$ESC[1mb$ESC[0m$ESC[2m|$ESC[0mc",
            joined.render(ansi = true),
        )
        assertEquals("a|b|c", joined.render(ansi = false))
    }

    @Test
    fun `an empty iterable gives empty text`() {
        val joined = emptyList<CharSequence>().joinToStyled()

        assertEquals("", joined.render(ansi = true))
        assertEquals("", joined.render(ansi = false))
    }

    @Test
    fun `an empty iterable gives the prefix and postfix in their own styles`() {
        assertEquals("[]", emptyList<CharSequence>().joinToStyled(prefix = "[", postfix = "]").render(ansi = true))
        assertEquals(
            "$ESC[2m[$ESC[0m$ESC[1m]$ESC[0m",
            emptyList<CharSequence>().joinToStyled(prefix = dim("["), postfix = bold("]")).render(ansi = true),
        )
    }

    @Test
    fun `prefix and postfix go around the pieces`() {
        val joined = pieces.joinToStyled(prefix = "[", postfix = bold("]"))

        assertEquals("[$ESC[31ma$ESC[0m, $ESC[1mb$ESC[0m, c$ESC[1m]$ESC[0m", joined.render(ansi = true))
        assertEquals("[a, b, c]", joined.render(ansi = false))
    }

    @Test
    fun `one element is written without a separator`() {
        val joined = listOf(red("a")).joinToStyled(prefix = "<", postfix = ">")

        assertEquals("<$ESC[31ma$ESC[0m>", joined.render(ansi = true))
    }

    @Test
    fun `the length is the length of the plain text`() {
        val joined = pieces.joinToStyled(separator = dim(" | "), prefix = "(", postfix = ")")

        assertEquals(pieces.joinToString(" | ", "(", ")").length, joined.length)
    }

    @Test
    fun `a join writes the same as the pieces joined with plus`() {
        val separator = dim(" | ")
        val joined = listOf(red("a"), "b").joinToStyled(separator)
        val added = red("a") + separator + "b"

        assertEquals(added.render(ansi = true), joined.render(ansi = true))
        assertEquals(added.render(ansi = false), joined.render(ansi = false))
    }
}
