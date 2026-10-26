package com.fromwau.kern.fuzzy

import kotlin.test.Test
import kotlin.test.assertEquals

class CorpusTest {

    private fun assertMisses(
        sets: List<CandidateSet>,
        size: Int,
        expected: List<String> = emptyList(),
    ) {
        assertEquals(size, sets.sumOf { it.cases.size })
        assertEquals(expected, misses(sets))
    }

    @Test
    fun `the command line corpus is all right`() = assertMisses(cliCorpus, 88)

    @Test
    fun `the first held out corpus is all right`() = assertMisses(heldOut1, 71)

    @Test
    fun `the second held out corpus misses only a doubled letter after a swap`() = assertMisses(
        heldOut2,
        85,
        // Two edits from a five-letter name, where one is allowed.
        listOf("docker: 'biuldd' > none (want build)"),
    )

    @Test
    fun `the third held out corpus is all right`() = assertMisses(heldOut3, 68)

    @Test
    fun `the toml keyword corpus is all right`() = assertMisses(tomlKeywords, 45)
}
