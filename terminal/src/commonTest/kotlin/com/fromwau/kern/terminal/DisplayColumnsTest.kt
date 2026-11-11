package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/** Widths as glibc 2.44's `wcwidth` gives them under C.UTF-8, a character that does not print counting one. */
class DisplayColumnsTest {

    @Test
    fun `a letter takes one column`() {
        assertEquals(3, "abc".displayColumns())
        assertEquals(2, "éü".displayColumns())
    }

    @Test
    fun `a character East Asian scripts write wide takes two`() {
        assertEquals(4, "日本".displayColumns())
        assertEquals(2, "\u3000".displayColumns())
        assertEquals(2, "\uFF21".displayColumns())
        assertEquals(2, "\u1100".displayColumns())
        assertEquals(2, "\u231A".displayColumns())
    }

    @Test
    fun `a wide character outside the basic plane takes two`() {
        assertEquals(2, "\uD83D\uDE00".displayColumns())
        assertEquals(2, "\uD83E\uDDFF".displayColumns())
        assertEquals(2, "\uD840\uDC00".displayColumns())
    }

    @Test
    fun `a combining mark and a zero width character take none`() {
        assertEquals(1, "e\u0301".displayColumns())
        assertEquals(0, "\u200B".displayColumns())
    }

    @Test
    fun `a soft hyphen and a character that does not print take one`() {
        assertEquals(1, "\u00AD".displayColumns())
        assertEquals(1, "\t".displayColumns())
    }

    @Test
    fun `a narrow or zero width character outside the basic plane takes one or none`() {
        assertEquals(1, "\uD835\uDC00".displayColumns())
        assertEquals(0, "\uD834\uDD67".displayColumns())
    }

    @Test
    fun `a lone surrogate takes one`() {
        assertEquals(2, "\uD83Dx".displayColumns())
    }
}
