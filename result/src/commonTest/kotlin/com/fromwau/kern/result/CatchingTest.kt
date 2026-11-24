package com.fromwau.kern.result

import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

private sealed interface DbError : ResultError {
    data class Failed(val reason: String?) : DbError
}

private class DriverException(message: String) : Exception(message)

private class PoolClosedException(message: String) : IllegalStateException(message)

class CatchingTest {
    @Test
    fun `catching gives the block's value as a success`() {
        assertEquals(Ok(3), catching(DriverException::class, { DbError.Failed(it.message) }) { 1 + 2 })
    }

    @Test
    fun `catching turns an exception of the named type into the error you make of it`() {
        val caught = catching(DriverException::class, { DbError.Failed(it.message) }) {
            throw DriverException("disk gone")
        }

        assertEquals(Err(DbError.Failed("disk gone")), caught)
    }

    @Test
    fun `catching turns a subtype of the named type into an error`() {
        val caught = catching(IllegalStateException::class, { DbError.Failed(it.message) }) {
            throw PoolClosedException("disk gone")
        }

        assertEquals(Err(DbError.Failed("disk gone")), caught)
    }

    @Test
    fun `catching throws on an exception of another type`() {
        val thrown = IllegalArgumentException("bad id")

        val failure = assertFailsWith<IllegalArgumentException> {
            val _ = catching(DriverException::class, { DbError.Failed(it.message) }) { throw thrown }
        }

        assertSame(thrown, failure)
    }

    @Test
    fun `catching lets cancellation through`() {
        assertFailsWith<CancellationException> {
            val _ = catching(DriverException::class, { DbError.Failed(it.message) }) {
                throw CancellationException("stop")
            }
        }
    }

    @Test
    fun `catching lets cancellation through when the named type is a supertype of it`() {
        assertFailsWith<CancellationException> {
            val _ = catching(Exception::class, { DbError.Failed(it.message) }) { throw CancellationException("stop") }
        }
    }

    @Test
    fun `catching lets an Error through`() {
        assertFailsWith<NotImplementedError> {
            val _ = catching(Exception::class, { DbError.Failed(it.message) }) { TODO("not yet") }
        }
    }
}
