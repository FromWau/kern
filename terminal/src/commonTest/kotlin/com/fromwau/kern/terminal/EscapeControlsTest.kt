package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class EscapeControlsTest {

    @Test
    fun `text is escaped as GNU ls escape style escapes a name`() {
        for (row in gnu) {
            val escape = row.escape ?: continue
            assertEquals(escape, row.name.escapeControls(), row.name)
        }
    }

    @Test
    fun `a space stays a space where GNU escape style writes a backslash before it`() {
        assertEquals("no key \"a b\" at .a", "no key \"a b\" at .a".escapeControls())
        assertEquals("a \\\\ b", "a \\ b".escapeControls())
    }

    @Test
    fun `a C1 control is escaped as its UTF-8 bytes`() {
        assertEquals("X\\302\\2332J\\302\\205", "X\u009B2J\u0085".escapeControls())
    }

    @Test
    fun `a line break and a tab are escaped unless the layout is kept`() {
        assertEquals("a\\nb\\tc", "a\nb\tc".escapeControls())
        assertEquals("a\nb\tc", "a\nb\tc".escapeControls(keepLayout = true))
        assertEquals("a\n\\033b\\r", "a\n\u001Bb\r".escapeControls(keepLayout = true))
    }

    @Test
    fun `an escaped character and the same text typed out look different`() {
        assertEquals("\\033", "\u001B".escapeControls())
        assertEquals("\\\\033", "\\033".escapeControls())
    }

    @Test
    fun `text with nothing to escape comes back as it is`() {
        val text = "café ü \u00A0 ✓ 'quoted' \"a\" \u202E"
        assertSame(text, text.escapeControls())
    }
}
