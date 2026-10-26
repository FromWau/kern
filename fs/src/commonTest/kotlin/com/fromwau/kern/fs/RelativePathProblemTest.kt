package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RelativePathProblemTest {
    @Test
    fun `a path of plain names has no problem`() {
        assertNull("a".relativePathProblem())
        assertNull("bin/tool".relativePathProblem())
        assertNull("a.b/.hidden/c d".relativePathProblem())
    }

    @Test
    fun `a leading slash is Absolute`() {
        assertEquals(RelativePathProblem.Absolute, "/etc/passwd".relativePathProblem())
    }

    @Test
    fun `a leading backslash is Absolute`() {
        assertEquals(RelativePathProblem.Absolute, "\\Windows".relativePathProblem())
    }

    @Test
    fun `a drive at the start is Absolute`() {
        assertEquals(RelativePathProblem.Absolute, "C:\\x".relativePathProblem())
        assertEquals(RelativePathProblem.Absolute, "c:x".relativePathProblem())
        assertEquals(RelativePathProblem.Absolute, "Z:".relativePathProblem())
    }

    @Test
    fun `a colon after more than one character is no drive`() {
        assertNull("ab:c".relativePathProblem())
        assertNull("1:x".relativePathProblem())
    }

    @Test
    fun `a dot dot name is BadName DotDot at its index`() {
        assertEquals(RelativePathProblem.BadName(1, "..", NameProblem.DotDot), "a/../b".relativePathProblem())
    }

    @Test
    fun `a dot name is BadName Dot`() {
        assertEquals(RelativePathProblem.BadName(0, ".", NameProblem.Dot), "./a".relativePathProblem())
    }

    @Test
    fun `two slashes in a row are a Blank name between them`() {
        assertEquals(RelativePathProblem.BadName(1, "", NameProblem.Blank), "a//b".relativePathProblem())
    }

    @Test
    fun `a trailing slash is a Blank name after it`() {
        assertEquals(RelativePathProblem.BadName(1, "", NameProblem.Blank), "bin/".relativePathProblem())
    }

    @Test
    fun `a backslash inside a name is Separator`() {
        assertEquals(RelativePathProblem.BadName(1, "b\\c", NameProblem.Separator), "a/b\\c".relativePathProblem())
    }

    @Test
    fun `a NUL in a name is Nul`() {
        assertEquals(
            RelativePathProblem.BadName(0, "a${Char(0)}", NameProblem.Nul),
            "a${Char(0)}/b".relativePathProblem(),
        )
    }

    @Test
    fun `the empty string is one Blank name`() {
        assertEquals(RelativePathProblem.BadName(0, "", NameProblem.Blank), "".relativePathProblem())
    }

    @Test
    fun `the first bad name is the one named`() {
        assertEquals(RelativePathProblem.BadName(0, "..", NameProblem.DotDot), "../a/..".relativePathProblem())
    }

    @Test
    fun `a name longer than 255 bytes is TooLong`() {
        val long = "z".repeat(256)

        assertEquals(
            RelativePathProblem.BadName(1, long, NameProblem.TooLong),
            "docs/$long/a.txt".relativePathProblem(),
        )
        assertNull("docs/${"z".repeat(255)}/a.txt".relativePathProblem())
    }

    @Test
    fun `a staged path judges its last name by the stage length`() {
        val over = "é".repeat(106) + "a"

        assertEquals(213, over.encodeToByteArray().size)
        assertEquals(
            RelativePathProblem.BadName(1, over, NameProblem.TooLongToStage),
            "packages/$over".relativePathProblem(staged = true),
        )
        assertNull("packages/${"é".repeat(106)}".relativePathProblem(staged = true))
        assertNull("packages/$over".relativePathProblem())
    }

    @Test
    fun `a staged path takes a folder name on the way of 255 bytes`() {
        assertNull("${"z".repeat(255)}/1.0".relativePathProblem(staged = true))
    }

    @Test
    fun `a staged path of a name over 255 bytes is TooLong`() {
        val long = "z".repeat(256)

        assertEquals(
            RelativePathProblem.BadName(0, long, NameProblem.TooLong),
            "$long/1.0".relativePathProblem(staged = true),
        )
        assertEquals(
            RelativePathProblem.BadName(1, long, NameProblem.TooLong),
            "packages/$long".relativePathProblem(staged = true),
        )
    }
}
