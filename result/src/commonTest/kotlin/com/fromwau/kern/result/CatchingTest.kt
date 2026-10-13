package com.fromwau.kern.result

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private sealed interface DbError : ResultError {
    data class Failed(val reason: String?) : DbError
}

class CatchingTest {
    @Test
    fun `catching gives the block's value as a success`() {
        assertEquals(Ok(3), catching({ DbError.Failed(it.message) }) { 1 + 2 })
    }

    @Test
    fun `catching turns an exception into the error you make of it`() {
        val caught = catching({ DbError.Failed(it.message) }) { error("disk gone") }

        assertEquals(Err(DbError.Failed("disk gone")), caught)
    }

    @Test
    fun `catching lets cancellation through`() {
        assertFailsWith<CancellationException> {
            val _ = catching({ DbError.Failed(it.message) }) { throw CancellationException("stop") }
        }
    }

    @Test
    fun `catching lets an Error through`() {
        assertFailsWith<NotImplementedError> {
            val _ = catching({ DbError.Failed(it.message) }) { TODO("not yet") }
        }
    }
}
