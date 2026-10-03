package com.fromwau.kern.fuzzy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuzzyTest {

    @Test
    fun `edit distance counts insertions deletions and substitutions`() {
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(0, editDistance("same", "same"))
        assertEquals(4, editDistance("", "four"))
        assertEquals(4, editDistance("four", ""))
        assertEquals(1, editDistance("ab", "ba"))
    }

    @Test
    fun `edit distance compares case exactly`() {
        assertEquals(3, editDistance("tcp", "TCP"))
    }

    @Test
    fun `a candidate that differs only in case is suggested`() {
        assertEquals("TCP", didYouMean("tcp", listOf("TCP", "udp")))
        assertEquals("fast", didYouMean("FAST", listOf("fast")))
        assertEquals("fast", didYouMean("Fast", listOf("fast")))
    }

    @Test
    fun `the only candidate the written word begins is suggested at any distance`() {
        assertEquals("--help", didYouMean("--h", listOf("--help", "--json")))
        assertEquals("unix", didYouMean("un", listOf("TCP", "udp", "unix")))
        assertEquals("FAST", didYouMean("fa", listOf("FAST")))
    }

    @Test
    fun `a prefix of several candidates falls back to the nearest`() {
        assertEquals("udp", didYouMean("u", listOf("udp", "unix")))
    }

    @Test
    fun `a blank word prefixes every candidate and so answers only when there is one`() {
        assertEquals("only", didYouMean("", listOf("only")))
        assertNull(didYouMean("", listOf("one", "two")))
        assertNull(didYouMean("", emptyList()))
    }

    @Test
    fun `a near miss within reach is suggested`() {
        assertEquals("list", didYouMean("lst", listOf("list", "add")))
        assertEquals("config", didYouMean("cofnig", listOf("config", "ping")))
        assertEquals("build", didYouMean("biuld", listOf("build", "add")))
        assertEquals("TCP", didYouMean("sctp", listOf("TCP", "udp")))
        assertEquals("udp", didYouMean("udpp", listOf("TCP", "udp")))
    }

    @Test
    fun `a longer candidate allows an edit for every three characters`() {
        assertEquals("square", didYouMean("sqare", listOf("circle", "point", "square")))
        assertEquals("circle", didYouMean("circel", listOf("circle", "point", "square")))
        assertEquals("configuration", didYouMean("confgiuraton", listOf("configuration")))
    }

    @Test
    fun `a word that shares nothing with a candidate is not suggested`() {
        assertNull(didYouMean("xy", listOf("ls", "rm")))
        assertEquals("ls", didYouMean("lx", listOf("ls", "rm")))
        assertNull(didYouMean("ab", listOf("xy")))
    }

    @Test
    fun `nothing is suggested when no candidate is close`() {
        assertNull(didYouMean("triangle", listOf("circle", "point", "square")))
        assertNull(didYouMean("zzzzzzzz", listOf("list", "add")))
        assertNull(didYouMean("x", emptyList()))
    }

    @Test
    fun `the written word itself is never suggested`() {
        assertNull(didYouMean("list", listOf("list", "add")))
        assertNull(didYouMean("udp", listOf("udp")))
    }

    @Test
    fun `the nearest of several close candidates wins`() {
        assertEquals("push", didYouMean("pusk", listOf("pull", "push")))
    }

    @Test
    fun `swapping two neighbouring characters counts as one edit`() {
        assertEquals(1, editDistance("ls", "sl"))
        assertEquals(1, editDistance("stauts", "status"))
    }

    @Test
    fun `swapped characters are not edited again`() {
        assertEquals(3, editDistance("ca", "abc"))
    }

    @Test
    fun `edit distance counts UTF-16 code units`() {
        assertEquals(2, editDistance("a\uD83D\uDE00", "a"))
    }

    @Test
    fun `swapped letters are suggested`() {
        assertEquals("sl", didYouMean("ls", listOf("sl")))
        assertEquals("ba", didYouMean("ab", listOf("ba")))
        assertEquals("status", didYouMean("stauts", listOf("status", "stash")))
    }

    @Test
    fun `a candidate that differs only in case wins over a unique prefix`() {
        assertEquals("AB", didYouMean("ab", listOf("abc", "AB")))
    }

    @Test
    fun `candidates that differ only in case count once as a prefix`() {
        assertEquals("status", didYouMean("sta", listOf("status", "STATUS")))
    }

    @Test
    fun `the candidate listed first wins a tie`() {
        assertEquals("bat", didYouMean("cat", listOf("bat", "cut")))
        assertEquals("cut", didYouMean("cat", listOf("cut", "bat")))
        assertEquals("TCP", didYouMean("Tcp", listOf("TCP", "tcp")))
    }

    @Test
    fun `candidates may be any collection`() {
        assertEquals("square", didYouMean("sqare", setOf("circle", "square")))
    }
}
