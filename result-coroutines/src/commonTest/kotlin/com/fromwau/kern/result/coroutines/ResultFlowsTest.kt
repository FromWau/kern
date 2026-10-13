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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private sealed interface LoadError : ResultError {
    data class Failed(val reason: String?) : LoadError

    data class Missing(val city: String) : LoadError
}

class ResultFlowsTest {
    @Test
    fun `asResult wraps each value and turns an upstream exception into one last error`() = runTest {
        val upstream = flow {
            emit(1)
            emit(2)
            throw IllegalStateException("offline")
        }

        val collected = upstream.asResult { LoadError.Failed(it.message) }.toList()

        assertEquals(listOf(Ok(1), Ok(2), Err(LoadError.Failed("offline"))), collected)
    }

    @Test
    fun `a cancellation thrown by the upstream while collecting is an error`() = runTest {
        val upstream = flow<Int> { throw CancellationException("stop") }

        val collected = upstream.asResult { LoadError.Failed(it.message) }.toList()

        assertEquals(listOf(Err(LoadError.Failed("stop"))), collected)
    }

    @Test
    fun `an exception the upstream throws while the collector is cancelled is not made an error`() = runTest {
        val upstream = flow {
            emit(1)
            try {
                awaitCancellation()
            } finally {
                throw IllegalStateException("cleanup")
            }
        }
        var mapped = false
        val collected = mutableListOf<Result<Int, LoadError>>()
        val job = launch(SupervisorJob() + CoroutineExceptionHandler { _, _ -> }) {
            upstream
                .asResult {
                    mapped = true
                    LoadError.Failed(it.message)
                }
                .collect { collected += it }
        }
        yield()

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(listOf<Result<Int, LoadError>>(Ok(1)), collected)
        assertFalse(mapped)
    }

    @Test
    fun `an exception the upstream throws while the collector is cancelled ends it with that exception`() = runTest {
        val upstream = flow<Int> {
            try {
                awaitCancellation()
            } finally {
                throw IllegalStateException("close failed")
            }
        }
        var cause: Throwable? = null
        val job = launch(SupervisorJob() + CoroutineExceptionHandler { _, _ -> }) {
            upstream.asResult { LoadError.Failed(it.message) }.collect {}
        }
        job.invokeOnCompletion { cause = it }
        yield()

        job.cancelAndJoin()

        val thrown = assertIs<IllegalStateException>(cause)
        assertEquals("close failed", thrown.message)
    }

    @Test
    fun `mapSuccess transforms successes with a suspending step and passes errors through`() = runTest {
        val results = flowOf<Result<Int, LoadError>>(Ok(2), Err(LoadError.Missing("Oslo")))

        val mapped = results.mapSuccess {
            delay(10)
            it * 10
        }.toList()

        assertEquals(listOf(Ok(20), Err(LoadError.Missing("Oslo"))), mapped)
    }

    @Test
    fun `mapSuccess throws on an exception from transform`() = runTest {
        val results = flowOf<Result<Int, LoadError>>(Ok(2))

        assertFailsWith<IllegalStateException> {
            val _ = results.mapSuccess { error("offline") }.toList()
        }
    }

    @Test
    fun `flatMapSuccess runs a suspending step that can fail with one item out for each in`() = runTest {
        val results = flowOf<Result<Int, LoadError>>(Ok(2), Err(LoadError.Missing("Oslo")), Ok(-1))

        val mapped = results.flatMapSuccess {
            delay(10)
            if (it < 0) Err(LoadError.Failed("negative")) else Ok(it * 10)
        }.toList()

        assertEquals(listOf(Ok(20), Err(LoadError.Missing("Oslo")), Err(LoadError.Failed("negative"))), mapped)
    }

    @Test
    fun `flatMapSuccess throws on an exception from transform`() = runTest {
        val results = flowOf<Result<Int, LoadError>>(Ok(2))

        assertFailsWith<IllegalStateException> {
            val _ = results.flatMapSuccess<Int, Int, LoadError> { error("offline") }.toList()
        }
    }
}
