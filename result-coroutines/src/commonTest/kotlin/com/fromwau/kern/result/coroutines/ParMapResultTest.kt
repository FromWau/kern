package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private sealed interface CityError : ResultError {
    data class Missing(val city: String) : CityError
}

// currentTime is how the tests see that the steps ran at once rather than one after another.
@OptIn(ExperimentalCoroutinesApi::class)
class ParMapResultTest {
    @Test
    fun `parMapResult runs the steps at once and keeps the input order`() = runTest {
        val loaded = listOf("Oslo", "Rome", "Lima").parMapResult { city ->
            delay(100)
            Ok(city.length)
        }

        assertEquals(Ok(listOf(4, 4, 4)), loaded)
        assertEquals(100, currentTime)
    }

    @Test
    fun `parMapResult ends at the first error and cancels the steps still running`() = runTest {
        var slowFinished = false

        val loaded = listOf("Oslo", "Rome").parMapResult { city ->
            if (city == "Rome") {
                delay(10)
                Err(CityError.Missing(city))
            } else {
                delay(1_000)
                slowFinished = true
                Ok(city.length)
            }
        }

        assertEquals(Err(CityError.Missing("Rome")), loaded)
        assertFalse(slowFinished)
        assertEquals(10, currentTime)
    }

    @Test
    fun `parMapResult gives the error that arrives first rather than the first in input order`() = runTest {
        val loaded = listOf("Oslo", "Rome").parMapResult<String, Int, CityError> { city ->
            delay(if (city == "Oslo") 50 else 10)
            Err(CityError.Missing(city))
        }

        assertEquals(Err(CityError.Missing("Rome")), loaded)
        assertEquals(10, currentTime)
    }

    @Test
    fun `parMapResult throws on an exception from transform`() = runTest {
        assertFailsWith<IllegalStateException> {
            val _ = listOf("Oslo").parMapResult<String, Int, CityError> { error("offline") }
        }
    }

    @Test
    fun `parMapResult throws an exception a cancelled step raises rather than giving the first error`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            val _ = listOf("Oslo", "Rome").parMapResult { city ->
                if (city == "Rome") {
                    delay(10)
                    Err(CityError.Missing(city))
                } else {
                    try {
                        delay(1_000)
                        Ok(city.length)
                    } finally {
                        error("cleanup failed")
                    }
                }
            }
        }

        assertEquals("cleanup failed", failure.message)
    }

    @Test
    fun `a step wrapped in suspendCatching that fails while cancelled throws that failure`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            val _ = listOf("Oslo", "Rome").parMapResult { city ->
                if (city == "Rome") {
                    delay(10)
                    Err(CityError.Missing(city))
                } else {
                    suspendCatching({ CityError.Missing(city) }) {
                        try {
                            delay(1_000)
                            city.length
                        } finally {
                            error("cleanup failed")
                        }
                    }
                }
            }
        }

        assertEquals("cleanup failed", failure.message)
    }

    @Test
    fun `parMapResult keeps every exception cancelled steps raise`() = runTest {
        val failure = assertFailsWith<IllegalStateException> {
            val _ = listOf("Oslo", "Bern", "Rome").parMapResult { city ->
                if (city == "Rome") {
                    delay(10)
                    Err(CityError.Missing(city))
                } else {
                    try {
                        delay(1_000)
                        Ok(city.length)
                    } finally {
                        error("$city cleanup failed")
                    }
                }
            }
        }

        val messages = (listOf(failure) + failure.suppressedExceptions).map { it.message }
        assertTrue("Oslo cleanup failed" in messages && "Bern cleanup failed" in messages, messages.toString())
    }

    @Test
    fun `parMapResult in a cancelled caller throws the cancellation rather than giving a result`() = runTest {
        var outcome: Result<List<Int>, CityError>? = null
        val job = launch { outcome = listOf("Oslo").parMapResult { awaitCancellation() } }
        yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(outcome)
    }

    @Test
    fun `parMapResult of nothing is an empty success`() = runTest {
        assertEquals(Ok(emptyList()), emptyList<String>().parMapResult<String, Int, CityError> { Ok(it.length) })
    }

    @Test
    fun `parMapResult with a concurrency runs no more steps at once than it allows`() = runTest {
        var running = 0
        var mostAtOnce = 0

        val loaded = listOf("a", "b", "c", "d", "e", "f").parMapResult(concurrency = 2) { city ->
            running++
            mostAtOnce = maxOf(mostAtOnce, running)
            delay(100)
            running--
            Ok(city.length)
        }

        assertEquals(Ok(listOf(1, 1, 1, 1, 1, 1)), loaded)
        assertEquals(2, mostAtOnce)
        assertEquals(300, currentTime)
    }

    @Test
    fun `parMapResult with a concurrency cancels the waiting steps at the first error`() = runTest {
        val started = mutableListOf<String>()

        val loaded = listOf("Rome", "Oslo", "Lima").parMapResult(concurrency = 1) { city ->
            started += city
            delay(10)
            if (city == "Rome") Err(CityError.Missing(city)) else Ok(city.length)
        }

        assertEquals(Err(CityError.Missing("Rome")), loaded)
        assertEquals(listOf("Rome"), started)
        assertEquals(10, currentTime)
    }

    @Test
    fun `parMapResult refuses a concurrency below one`() = runTest {
        assertFailsWith<IllegalArgumentException> { listOf("a").parMapResult(concurrency = 0) { Ok(it) } }
    }
}
