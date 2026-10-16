package com.fromwau.kern.result

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private sealed interface StoreError : ResultError {
    data class NotFound(val id: Long) : StoreError

    data object Full : StoreError
}

private sealed interface BlankError : ResultError {
    data object Blank : BlankError
}

class BindingTest {
    private fun toBlank(error: StoreError): BlankError = BlankError.Blank

    private fun find(id: Long): Result<String, StoreError> = if (id == 1L) Ok("ada") else Err(StoreError.NotFound(id))

    private fun save(name: String): Result<Int, StoreError.Full> =
        if (name.isEmpty()) Err(StoreError.Full) else Ok(name.length)

    @Test
    fun `a block whose binds all succeed gives its value`() {
        val saved: Result<Int, StoreError> = result {
            val name = find(1).bind()
            save(name).bind()
        }

        assertEquals(Ok(3), saved)
    }

    @Test
    fun `the first error ends the block and is its result`() {
        var reachedSave = false

        val saved: Result<Int, StoreError> = result {
            val name = find(2).bind()
            reachedSave = true
            save(name).bind()
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
        assertFalse(reachedSave)
    }

    @Test
    fun `a narrower error binds into the block's error type`() {
        val saved: Result<Int, StoreError> = result { save("").bind() }

        assertEquals(Err(StoreError.Full), saved)
    }

    @Test
    fun `a bind with a conversion gives the value of a success`() {
        val name: Result<String, BlankError> = result { find(1).bind { BlankError.Blank } }

        assertEquals(Ok("ada"), name)
    }

    @Test
    fun `a bind with a conversion ends the block with the converted error`() {
        val name: Result<String, BlankError> = result { find(2).bind(::toBlank) }

        assertEquals(Err(BlankError.Blank), name)
    }

    @Test
    fun `a nested block keeps its own error and the outer block goes on`() {
        val outer: Result<String, StoreError> = result {
            val inner: Result<Int, BlankError> = result { Err(BlankError.Blank).bind() }
            assertEquals(Err(BlankError.Blank), inner)
            find(1).bind()
        }

        assertEquals(Ok("ada"), outer)
    }

    @Test
    fun `an error bound in a nested block for the outer block ends the outer one`() {
        val outer: Result<String, StoreError> = result {
            val scope = this
            val inner: Result<Int, BlankError> = result { with(scope) { find(2).bind() }.length }
            "never: $inner"
        }

        assertEquals(Err(StoreError.NotFound(2)), outer)
    }

    @Test
    fun `a catch of Exception inside the block does not swallow a bind`() {
        var caught = false
        val saved: Result<String, StoreError> = result {
            try {
                find(2).bind()
            } catch (e: Exception) {
                caught = true
                "swallowed"
            }
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
        assertFalse(caught)
    }

    @Test
    fun `catching inside the block does not swallow a bind`() {
        var caught = false
        val saved: Result<Result<String, StoreError>, StoreError> = result {
            catching<String, StoreError>({
                caught = true
                StoreError.Full
            }) { find(2).bind() }
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
        assertFalse(caught)
    }

    @Test
    fun `a catch of Throwable that swallows a bind still ends the block with its error`() {
        val saved: Result<String, StoreError> = result {
            try {
                find(2).bind()
            } catch (t: Throwable) {
                "swallowed"
            }
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
    }

    @Test
    fun `a runCatching that swallows a bind still ends the block with its error`() {
        val saved: Result<String, StoreError> = result { runCatching { find(2).bind() }.getOrElse { "fallback" } }

        assertEquals(Err(StoreError.NotFound(2)), saved)
    }

    @Test
    fun `after a swallowed failure the next bind exits even on a success`() {
        var reachedEnd = false

        val saved: Result<String, StoreError> = result {
            val _ = runCatching { find(2).bind() }
            find(1).bind()
            reachedEnd = true
            "done"
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
        assertFalse(reachedEnd)
    }

    @Test
    fun `after a swallowed failure a later failing bind still ends with the first error`() {
        val saved: Result<String, StoreError> = result {
            val _ = runCatching { find(2).bind() }
            find(3).bind()
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
    }

    @Test
    fun `a bind failing in a coroutine launched into another scope still ends the block with its error`() = runTest {
        val other = CoroutineScope(SupervisorJob() + CoroutineExceptionHandler { _, _ -> })

        val saved: Result<Int, StoreError> = result {
            other.launch { find(2).bind() }.join()
            7
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
    }

    @Test
    fun `a finally inside the block runs when a bind ends it`() {
        var cleanedUp = false

        val saved: Result<String, StoreError> = result {
            try {
                find(2).bind()
            } finally {
                cleanedUp = true
            }
        }

        assertEquals(Err(StoreError.NotFound(2)), saved)
        assertTrue(cleanedUp)
    }

    @Test
    fun `a block without an expected type infers its error type from its binds`() {
        val found = result { find(1).bind() }

        assertEquals<Result<String, StoreError>>(Ok("ada"), found)
    }

    @Test
    fun `a bind run after its block returned fails with an illegal state`() {
        val mapped: Result<Sequence<Int>, StoreError> = result { sequenceOf(1).map { find(2).bind().length } }
        val lengths = assertIs<Result.Success<Sequence<Int>>>(mapped).value

        val failure = assertFailsWith<IllegalStateException> { lengths.toList() }

        assertEquals("bind() called after its result block returned", failure.message)
    }

    @Test
    fun `an exception thrown in the block passes through`() {
        assertFailsWith<IllegalStateException> {
            val _: Result<Int, StoreError> = result { error("boom") }
        }
    }

    @Test
    fun `cancellation thrown in the block passes through`() {
        assertFailsWith<CancellationException> {
            val _: Result<Int, StoreError> = result { throw CancellationException("stop") }
        }
    }

    @Test
    fun `a block may suspend between binds`() = runTest {
        val saved: Result<Int, StoreError> = result {
            val name = find(1).bind()
            delay(10)
            save(name).bind()
        }

        assertEquals(Ok(3), saved)
    }
}
