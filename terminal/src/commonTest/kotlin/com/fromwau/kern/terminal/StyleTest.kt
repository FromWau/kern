package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

private val ESC = Char(27)

class StyleTest {

    @Test
    fun `styles are equal whatever the order and repetition of their parts`() {
        assertEquals(bold + red, red + bold)
        assertEquals((bold + red).hashCode(), (red + bold).hashCode())
        assertEquals(red, red + red)
        assertEquals(red, plain + red)
        assertNotEquals(red, bold)
    }

    @Test
    fun `a style is named after its parts`() {
        assertEquals("bold+red", (red + bold).toString())
        assertEquals("red", red.toString())
        assertEquals("plain", plain.toString())
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a style wraps the text in its codes and a reset`() {
        assertEquals("$ESC[1;31mfailed$ESC[0m", (bold + red).render("failed", enabled = true))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `a disabled style leaves the text untouched`() {
        assertEquals("failed", red.render("failed", enabled = false))
    }

    @Suppress("DEPRECATION")
    @Test
    fun `text after a nested style keeps the outer style`() {
        val inner = bold.render("b", enabled = true)
        val outer = red.render("a $inner c", enabled = true)

        assertEquals("$ESC[31ma $ESC[1mb$ESC[0m$ESC[31m c$ESC[0m", outer)
    }
}
