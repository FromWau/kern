package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

private data object Lost : ResultError

class BindSignalTest {
    @Test
    fun `a failing bind throws a signal without a stack trace`() {
        val scope = ResultScope<Lost>()

        val signal = assertFailsWith<BindSignal> {
            with(scope) { Err(Lost).bind() }
        }

        assertTrue(signal.stackTrace.isEmpty(), "stack trace of ${signal.stackTrace.size} frames")
    }

    @Test
    fun `an exception thrown in a result block keeps its stack trace`() {
        val thrown = IllegalStateException("boom")

        val passed = assertFailsWith<IllegalStateException> {
            val _: Result<Int, Lost> = result { throw thrown }
        }

        assertSame(thrown, passed)
        assertTrue(passed.stackTrace.any { it.className.startsWith(BindSignalTest::class.java.name) })
    }
}
