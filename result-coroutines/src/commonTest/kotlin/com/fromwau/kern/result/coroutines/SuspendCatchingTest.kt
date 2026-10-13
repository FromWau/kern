package com.fromwau.kern.result.coroutines

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private sealed interface FetchError : ResultError {
    data class Failed(val reason: String?) : FetchError
}

class SuspendCatchingTest {
    @Test
    fun `suspendCatching gives the value of a block that suspends`() = runTest {
        val fetched = suspendCatching({ FetchError.Failed(it.message) }) {
            delay(10)
            "ada"
        }

        assertEquals(Ok("ada"), fetched)
    }

    @Test
    fun `suspendCatching turns an exception into the error you make of it`() = runTest {
        val fetched = suspendCatching({ FetchError.Failed(it.message) }) { error("timeout") }

        assertEquals(Err(FetchError.Failed("timeout")), fetched)
    }

    @Test
    fun `a cancelled coroutine stays cancelled rather than ending in an error`() = runTest {
        var outcome: Result<String, FetchError>? = null
        val job = launch { outcome = suspendCatching({ FetchError.Failed(it.message) }) { awaitCancellation() } }
        yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertNull(outcome)
    }

    @Test
    fun `a cancellation thrown while the coroutine is still active is an error like any other`() = runTest {
        val fetched = suspendCatching({ FetchError.Failed(it.message) }) { throw CancellationException("inner") }

        assertEquals(Err(FetchError.Failed("inner")), fetched)
    }

    @Test
    fun `an exception the block throws while the coroutine is cancelled ends it with that exception`() = runTest {
        var cause: Throwable? = null
        val job = launch(SupervisorJob() + CoroutineExceptionHandler { _, _ -> }) {
            val _ = suspendCatching({ FetchError.Failed(it.message) }) {
                try {
                    awaitCancellation()
                } finally {
                    throw IllegalStateException("close failed")
                }
            }
        }
        job.invokeOnCompletion { cause = it }
        yield()

        job.cancelAndJoin()

        val thrown = assertIs<IllegalStateException>(cause)
        assertEquals("close failed", thrown.message)
    }
}
