package com.fromwau.kern.result

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

private data class Missing(val id: Long) : ResultError

class NestedRunBlockingTest {
    private fun find(id: Long): Result<String, Missing> = Err(Missing(id))

    @Test
    fun `a bind failing in a nested runBlocking ends the block with its error`() {
        val found: Result<Int, Missing> = result {
            runBlocking { find(2).bind() }
            7
        }

        assertEquals(Err(Missing(2)), found)
    }
}
