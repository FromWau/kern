package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private sealed interface Probe : IError {
    data class Missing(val id: Long) : Probe

    data object Busy : Probe
}

class ResultAssertionsTest {
    private val found: Result<String, Probe> = Ok("user")
    private val missing: Result<String, Probe> = Err(Probe.Missing(7))

    @Test
    fun `assertSuccess gives the value of a success`() {
        assertEquals("user", found.assertSuccess())
    }

    @Test
    fun `assertSuccess on an error fails naming the error`() {
        val failure = assertFailsWith<AssertionError> { missing.assertSuccess() }

        assertTrue("Missing(id=7)" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertError gives the error as the case asked for`() {
        assertEquals(Probe.Missing(7), missing.assertError<Probe.Missing>())
    }

    @Test
    fun `assertError on another case fails naming the case that came`() {
        val failure = assertFailsWith<AssertionError> { missing.assertError<Probe.Busy>() }

        assertTrue("Missing(id=7)" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertError on a success fails naming the value`() {
        val failure = assertFailsWith<AssertionError> { found.assertError<Probe.Missing>() }

        assertTrue("user" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertSuccess with an expected value passes on an equal success`() {
        found.assertSuccess("user")
    }

    @Test
    fun `assertSuccess with an expected value fails naming both values on another success`() {
        val failure = assertFailsWith<AssertionError> { found.assertSuccess("admin") }

        assertTrue("admin" in failure.message.orEmpty() && "user" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `assertSuccess with an expected value on an error fails naming the value and the error`() {
        val failure = assertFailsWith<AssertionError> { missing.assertSuccess("user") }

        val message = failure.message.orEmpty()
        assertTrue("user" in message && "Missing(id=7)" in message, failure.message)
    }

    @Test
    fun `assertError with an expected error passes on an equal error`() {
        missing.assertError(Probe.Missing(7))
    }

    @Test
    fun `assertError with an expected error fails naming both errors on another error`() {
        val failure = assertFailsWith<AssertionError> { missing.assertError(Probe.Missing(8)) }

        val message = failure.message.orEmpty()
        assertTrue("Missing(id=8)" in message && "Missing(id=7)" in message, failure.message)
    }

    @Test
    fun `assertError with an expected error on a success fails naming the value`() {
        val failure = assertFailsWith<AssertionError> { found.assertError(Probe.Busy) }

        assertTrue("user" in failure.message.orEmpty(), failure.message)
    }
}
