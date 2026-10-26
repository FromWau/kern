package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameProblemTest {
    @Test
    fun `an ordinary name has no problem`() {
        assertNull("app.toml".nameProblem())
        assertNull(".hidden".nameProblem())
        assertNull("...".nameProblem())
        assertNull(" a ".nameProblem())
    }

    @Test
    fun `a name holding a NUL is Nul`() {
        assertEquals(NameProblem.Nul, "a\u0000b".nameProblem())
    }

    @Test
    fun `an empty or whitespace name is Blank`() {
        assertEquals(NameProblem.Blank, "".nameProblem())
        assertEquals(NameProblem.Blank, " \t".nameProblem())
    }

    @Test
    fun `a dot or two dots name a folder rather than an entry in it`() {
        assertEquals(NameProblem.Dot, ".".nameProblem())
        assertEquals(NameProblem.DotDot, "..".nameProblem())
    }

    @Test
    fun `a slash or a backslash is a Separator on every platform`() {
        assertEquals(NameProblem.Separator, "a/b".nameProblem())
        assertEquals(NameProblem.Separator, "a\\b".nameProblem())
        assertEquals(NameProblem.Separator, "/".nameProblem())
    }

    @Test
    fun `the longest name kern accepts is 255 bytes`() {
        assertEquals(255, LONGEST_NAME_BYTES)
    }

    @Test
    fun `a name of 255 bytes in UTF-8 has no problem`() {
        assertNull("a".repeat(255).nameProblem())
    }

    @Test
    fun `a name of 256 bytes in UTF-8 is TooLong`() {
        assertEquals(NameProblem.TooLong, "a".repeat(256).nameProblem())
    }

    @Test
    fun `a name of 128 two-byte characters is TooLong though it holds only 128 characters`() {
        val name = "é".repeat(128)

        assertEquals(256, name.encodeToByteArray().size)
        assertEquals(NameProblem.TooLong, name.nameProblem())
        assertNull("é".repeat(127).nameProblem())
    }

    @Test
    fun `a staged name of 212 bytes has no problem`() {
        assertNull("a".repeat(212).nameProblem(staged = true))
        assertNull("é".repeat(106).nameProblem(staged = true))
    }

    @Test
    fun `a staged name of 213 or 255 bytes is TooLongToStage`() {
        assertEquals(NameProblem.TooLongToStage, "a".repeat(213).nameProblem(staged = true))
        assertEquals(NameProblem.TooLongToStage, "a".repeat(255).nameProblem(staged = true))
        assertEquals(NameProblem.TooLongToStage, "€".repeat(71).nameProblem(staged = true))
        assertEquals(NameProblem.TooLongToStage, "${"é".repeat(127)}a".nameProblem(staged = true))
    }

    @Test
    fun `a staged name of 256 bytes is TooLong`() {
        assertEquals(NameProblem.TooLong, "a".repeat(256).nameProblem(staged = true))
        assertEquals(NameProblem.TooLong, "é".repeat(128).nameProblem(staged = true))
    }

    @Test
    fun `a name of 213 bytes that is not staged has no problem`() {
        assertNull("a".repeat(213).nameProblem())
        assertNull("€".repeat(71).nameProblem())
    }

    @Test
    fun `a long name holding a separator is a Separator`() {
        assertEquals(NameProblem.Separator, "a/${"b".repeat(300)}".nameProblem())
    }
}
