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
}
