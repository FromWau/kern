package com.fromwau.kern.exec

import kotlin.test.Test
import kotlin.test.assertEquals

class Utf8StreamTest {
    @Test
    fun `a character split across two reads comes whole with the second`() {
        val stream = Utf8Stream()
        val euro = "€".encodeToByteArray()
        assertEquals("a", stream.feed(byteArrayOf('a'.code.toByte(), euro[0]), 2))
        assertEquals("€b", stream.feed(byteArrayOf(euro[1], euro[2], 'b'.code.toByte()), 3))
    }

    @Test
    fun `a malformed byte is U+FFFD and decoding goes on`() =
        assertEquals("a�b", Utf8Stream().feed(byteArrayOf(0x61, 0xFF.toByte(), 0x62), 3))

    @Test
    fun `an unfinished sequence at the end is U+FFFD`() {
        val stream = Utf8Stream()
        assertEquals("", stream.feed(byteArrayOf(0xE2.toByte()), 1))
        assertEquals("�", stream.end())
    }

    @Test
    fun `a four-byte character split three ways comes once whole`() {
        val stream = Utf8Stream()
        val smiley = "😀".encodeToByteArray()
        assertEquals("", stream.feed(smiley.copyOfRange(0, 1), 1))
        assertEquals("", stream.feed(smiley.copyOfRange(1, 3), 2))
        assertEquals("😀", stream.feed(smiley.copyOfRange(3, 4), 1))
    }

    @Test
    fun `only count bytes of the buffer are read`() =
        assertEquals("a", Utf8Stream().feed(byteArrayOf(0x61, 0x62), 1))

    @Test
    fun `a prefix that can never complete is released at once`() {
        val stream = Utf8Stream()
        assertEquals("\uFFFD\uFFFD", stream.feed(byteArrayOf(0xE0.toByte(), 0x80.toByte()), 2))
        assertEquals("", stream.end())
    }

    @Test
    fun `a surrogate prefix is two U+FFFD at once`() {
        val stream = Utf8Stream()
        assertEquals("\uFFFD\uFFFD", stream.feed(bytes(0xED, 0xA0), 2))
        assertEquals("", stream.end())
    }

    @Test
    fun `a prefix past U+10FFFF is two U+FFFD at once`() {
        val stream = Utf8Stream()
        assertEquals("\uFFFD\uFFFD", stream.feed(bytes(0xF4, 0x90), 2))
        assertEquals("", stream.end())
    }

    @Test
    fun `a surrogate prefix alone is two U+FFFD however split`() = assertEverySplit("\uFFFD\uFFFD", 0xED, 0xA0)

    @Test
    fun `an encoded surrogate is three U+FFFD however split`() =
        assertEverySplit("\uFFFD\uFFFD\uFFFD", 0xED, 0xA0, 0x80)

    @Test
    fun `an overlong three-byte prefix is two U+FFFD however split`() = assertEverySplit("\uFFFD\uFFFD", 0xE0, 0x80)

    @Test
    fun `a code point past U+10FFFF is four U+FFFD however split`() =
        assertEverySplit("\uFFFD\uFFFD\uFFFD\uFFFD", 0xF4, 0x90, 0x80, 0x80)

    @Test
    fun `an overlong two-byte NUL is two U+FFFD however split`() = assertEverySplit("\uFFFD\uFFFD", 0xC0, 0x80)

    @Test
    fun `a lead byte past F4 is one U+FFFD`() = assertEverySplit("\uFFFD", 0xF5)

    @Test
    fun `a lone continuation byte is one U+FFFD`() = assertEverySplit("\uFFFD", 0x80)

    @Test
    fun `a truncated sequence at the end is one U+FFFD however split`() = assertEverySplit("\uFFFD", 0xE2, 0x82)

    @Test
    fun `a bad sequence between text keeps the text however split`() =
        assertEverySplit("a\uFFFD\uFFFD\uFFFDb", 0x61, 0xED, 0xA0, 0x80, 0x62)

    @Test
    fun `a broken sequence before a valid character keeps the character however split`() =
        assertEverySplit("\uFFFD€", 0xE2, 0x82, 0xE2, 0x82, 0xAC)

    @Test
    fun `a stray continuation byte is U+FFFD`() =
        assertEquals("\uFFFD", Utf8Stream().feed(byteArrayOf(0x80.toByte()), 1))

    @Test
    fun `an always invalid lead byte is U+FFFD at once`() {
        val stream = Utf8Stream()
        assertEquals("\uFFFD", stream.feed(byteArrayOf(0xC0.toByte()), 1))
        assertEquals("", stream.end())
    }

    @Test
    fun `a valid held prefix still waits`() {
        val stream = Utf8Stream()
        assertEquals("", stream.feed(byteArrayOf(0xF0.toByte(), 0x9F.toByte()), 2))
        assertEquals("😀", stream.feed("😀".encodeToByteArray().copyOfRange(2, 4), 2))
    }

    /** [input] decodes to [expected] in one read and in two reads split at every point. */
    private fun assertEverySplit(
        expected: String,
        vararg input: Int,
    ) {
        val data = bytes(*input)
        for (cut in 0..data.size) {
            val stream = Utf8Stream()
            val first = stream.feed(data.copyOfRange(0, cut), cut)
            val second = stream.feed(data.copyOfRange(cut, data.size), data.size - cut)
            assertEquals(expected, first + second + stream.end(), "split at $cut")
        }
    }

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
