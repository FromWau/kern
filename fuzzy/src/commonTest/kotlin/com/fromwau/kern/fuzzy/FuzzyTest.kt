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
    fun `a single character is no prefix to go on`() {
        assertNull(didYouMean("u", listOf("udp", "unix")))
        assertNull(didYouMean("t", listOf("true", "false")))
        assertEquals("false", didYouMean("fa", listOf("true", "false")))
    }

    @Test
    fun `a blank word suggests nothing`() {
        assertNull(didYouMean("", listOf("only")))
        assertNull(didYouMean("", listOf("one", "two")))
        assertNull(didYouMean("", emptyList()))
    }

    @Test
    fun `a near miss within reach is suggested`() {
        assertEquals("list", didYouMean("lst", listOf("list", "add")))
        assertEquals("config", didYouMean("cofnig", listOf("config", "ping")))
        assertEquals("build", didYouMean("biuld", listOf("build", "add")))
        assertEquals("udp", didYouMean("udpp", listOf("TCP", "udp")))
    }

    @Test
    fun `a candidate of up to five characters allows one edit`() {
        assertEquals("true", didYouMean("tru", listOf("true", "false")))
        assertNull(didYouMean("value", listOf("true", "false")))
        assertEquals("false", didYouMean("fasle", listOf("true", "false")))
        assertNull(didYouMean("sctp", listOf("TCP", "udp")))
        assertNull(didYouMean("tr", listOf("tcp", "true", "trap")))
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
    fun `a replaced character in a word of up to three characters is not close`() {
        assertNull(didYouMean("lx", listOf("ls", "rm")))
        assertNull(didYouMean("can", listOf("nan", "true")))
        assertEquals("nan", didYouMean("an", listOf("nan")))
        assertEquals("nan", didYouMean("nna", listOf("nan")))
        assertEquals("ls", didYouMean("sl", listOf("ls", "rm")))
    }

    @Test
    fun `the candidate listed first wins a tie`() {
        assertEquals("bast", didYouMean("bart", listOf("bast", "bant")))
        assertEquals("bant", didYouMean("bart", listOf("bant", "bast")))
        assertEquals("TCP", didYouMean("Tcp", listOf("TCP", "tcp")))
    }

    @Test
    fun `candidates may be any collection`() {
        assertEquals("square", didYouMean("sqare", setOf("circle", "square")))
    }

    @Test
    fun `a close preferred candidate wins over every other`() {
        val keywords = listOf("nan", "inf", "true", "false")
        assertEquals("true", didYouMean("tru", keywords, preferred = listOf("true", "false")))
        assertEquals("false", didYouMean("fasle", listOf("falsy", "false"), preferred = listOf("false")))
        assertEquals("inf", didYouMean("inff", listOf("info", "inf"), preferred = listOf("inf")))
    }

    @Test
    fun `preferred candidates still have to be close`() {
        assertEquals("nano", didYouMean("nanu", listOf("true", "false", "nano"), preferred = listOf("true", "false")))
        assertNull(didYouMean("t", listOf("true", "false"), preferred = listOf("true", "false")))
    }

    @Test
    fun `preferred candidates rank among themselves as any others`() {
        val preferred = listOf("false", "true")
        assertEquals("true", didYouMean("ture", listOf("false", "true"), preferred))
        assertEquals("bant", didYouMean("bart", listOf("bant", "bast"), preferred = listOf("bant", "bast")))
    }

    @Test
    fun `a preferred name that is no candidate is ignored`() {
        assertNull(didYouMean("tru", listOf("nan"), preferred = listOf("true")))
    }
}
