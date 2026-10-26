package com.fromwau.kern.fuzzy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// The README's snippets, kept here so they compile against the real API and do what the README says.
class ReadmeSnippetsTest {
    private val printed = mutableListOf<String>()

    /** Stands in for the `println` the README calls, so a test can read what it prints. */
    private fun println(message: String) {
        printed += message
    }

    @Test
    fun `an unknown command is answered with the command it most likely meant`() {
        val commands = listOf("status", "commit", "push")
        val written = "stauts"
        if (written !in commands) {
            didYouMean(written, commands)?.let { println("unknown command '$written', did you mean '$it'?") }
        }

        assertEquals(listOf("unknown command 'stauts', did you mean 'status'?"), printed)
    }

    @Test
    fun `the examples of the rules find each name and none for an unrelated word`() {
        assertEquals("square", didYouMean("sqare", listOf("circle", "point", "square")))
        assertEquals("TCP", didYouMean("tcp", listOf("TCP", "udp")))
        assertEquals("--output", didYouMean("--o", listOf("--output", "--json")))
        assertEquals("warn", didYouMean("warning", listOf("debug", "info", "warn")))
        assertNull(didYouMean("triangle", listOf("circle", "square")))
    }

    @Test
    fun `preferred names are found first and a short preferred name beats a longer other name`() {
        val keywords = listOf("nan", "inf", "true", "false")

        assertEquals("true", didYouMean("tru", keywords, preferred = listOf("true", "false")))
        assertNull(didYouMean("t", keywords, preferred = listOf("true", "false")))
        assertEquals("log", didYouMean("logsx", listOf("logs"), preferred = listOf("log")))
    }

    @Test
    fun `a typo of an alias maps back to the name it stands for`() {
        val written = "dle"

        val aliases = mapOf("rm" to "remove", "del" to "remove", "ls" to "list")
        val meant = didYouMean(written, listOf("remove", "list") + aliases.keys)?.let { aliases[it] ?: it }

        assertEquals("remove", meant)
    }

    @Test
    fun `edit distance counts a swap as one edit and case as different characters`() {
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(1, editDistance("ls", "sl"))
        assertEquals(3, editDistance("tcp", "TCP"))
    }
}
