package com.fromwau.kern.fuzzy

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BoundedDistanceTest {

    private val random = Random(20_261_018)

    private fun word(): String = (1..random.nextInt(13)).map { "abcd"[random.nextInt(4)] }.joinToString("")

    private fun edited(base: String): String {
        val chars = base.toMutableList()
        repeat(random.nextInt(5)) {
            val at = if (chars.isEmpty()) 0 else random.nextInt(chars.size)
            when (random.nextInt(4)) {
                0 -> chars.add(at, "abcd"[random.nextInt(4)])
                1 -> if (chars.isNotEmpty()) chars.removeAt(at)
                2 -> if (chars.isNotEmpty()) chars[at] = "abcd"[random.nextInt(4)]
                else -> if (at + 1 < chars.size) chars[at] = chars[at + 1].also { chars[at + 1] = chars[at] }
            }
        }
        return chars.joinToString("")
    }

    @Test
    fun `a bounded distance equals the edit distance within the limit and is absent beyond it`() {
        repeat(20_000) {
            val from = word()
            val to = if (random.nextInt(4) == 0) word() else edited(from)
            val limit = random.nextInt(7)
            val exact = editDistance(from, to)
            val bounded = editDistanceWithin(from, to, limit)
            if (exact <= limit) {
                assertEquals(exact, bounded, "'$from' to '$to' within $limit")
            } else {
                assertNull(bounded, "'$from' to '$to' within $limit is $exact")
            }
        }
    }

    @Test
    fun `a swap counts once within the limit`() {
        assertEquals(1, editDistanceWithin("stauts", "status", 1))
        assertNull(editDistanceWithin("ca", "abc", 2))
    }
}
