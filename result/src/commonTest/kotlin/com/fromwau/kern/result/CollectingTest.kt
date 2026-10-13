package com.fromwau.kern.result

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs

private sealed interface FieldError : ResultError {
    data class BadPort(val given: String) : FieldError

    data object EmptyHost : FieldError
}

private sealed interface NameError : ResultError {
    data object Blank : NameError
}

private data class Endpoint(
    val host: String,
    val port: Int,
)

private const val RACE_ROUNDS = 2000
private const val CONCURRENT_WORKERS = 4
private const val STEPS_PER_WORKER = 250

@OptIn(ExperimentalAtomicApi::class)
class CollectingTest {
    private fun port(raw: String): Result<Int, FieldError.BadPort> =
        raw.toIntOrNull()?.let { Ok(it) } ?: Err(FieldError.BadPort(raw))

    private fun host(raw: String): Result<String, FieldError> =
        if (raw.isBlank()) Err(FieldError.EmptyHost) else Ok(raw)

    @Test
    fun `a block whose collected steps all succeed gives its value`() {
        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            val host by host("example.org").collect()
            val port by port("80").collect()
            Endpoint(host, port)
        }

        assertEquals(Ok(Endpoint("example.org", 80)), endpoint)
    }

    @Test
    fun `every failed step is collected in order before a failed value ends the block`() {
        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            val host by host(" ").collect()
            val port by port("x").collect()
            Endpoint(host, port)
        }

        assertEquals(Err(AllErrors(listOf(FieldError.EmptyHost, FieldError.BadPort("x")))), endpoint)
    }

    @Test
    fun `a collected value reads inline through value`() {
        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            Endpoint(host("example.org").collect().value, port("x").collect().value)
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x")))), endpoint)
    }

    @Test
    fun `reading a failed step before collecting the next leaves out the next step's error`() {
        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            val port by port("x").collect()
            val url = "http://localhost:$port"
            val host by host(" ").collect()
            Endpoint(host, url.length)
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x")))), endpoint)
    }

    @Test
    fun `a collect with a conversion collects the converted error`() {
        val checked: Result<String, AllErrors<NameError>> = resultAll {
            val host by host(" ").collect { NameError.Blank }
            val port by port("x").collect { NameError.Blank }
            "$host:$port"
        }

        assertEquals(Err(AllErrors(listOf(NameError.Blank, NameError.Blank))), checked)
    }

    @Test
    fun `a bind with a conversion ends the block with the errors so far and its converted one`() {
        val checked: Result<String, AllErrors<NameError>> = resultAll {
            val _ = port("x").collect { NameError.Blank }
            host(" ").bind { NameError.Blank }
        }

        assertEquals(Err(AllErrors(listOf(NameError.Blank, NameError.Blank))), checked)
    }

    @Test
    fun `a failed step that is never read still fails the block`() {
        val checked: Result<String, AllErrors<FieldError>> = resultAll {
            val _ = port("x").collect()
            "checked"
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x")))), checked)
    }

    @Test
    fun `a bind in the block ends it with the errors so far and its own`() {
        var reachedEnd = false

        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            val port by port("x").collect()
            val host = host(" ").bind()
            reachedEnd = true
            Endpoint(host, port)
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x"), FieldError.EmptyHost))), endpoint)
        assertFalse(reachedEnd)
    }

    @Test
    fun `a runCatching around a failed read cannot make the block succeed`() {
        val endpoint: Result<Int, AllErrors<FieldError>> = resultAll {
            val port by port("x").collect()
            runCatching { port }.getOrElse { 8080 }
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x")))), endpoint)
    }

    @Test
    fun `a nested result block keeps its own error`() {
        val endpoint: Result<Endpoint, AllErrors<FieldError>> = resultAll {
            val name: Result<String, NameError> = result { Err(NameError.Blank).bind() }
            assertEquals(Err(NameError.Blank), name)
            val host by host("example.org").collect()
            val port by port("80").collect()
            Endpoint(host, port)
        }

        assertEquals(Ok(Endpoint("example.org", 80)), endpoint)
    }

    @Test
    fun `reading an outer block's failed value in a nested block ends the outer block`() {
        val outer: Result<Int, AllErrors<FieldError>> = resultAll {
            val port by port("x").collect()
            val inner: Result<Int, AllErrors<NameError>> = resultAll { port }
            inner.getOrElse { -1 }
        }

        assertEquals(Err(AllErrors(listOf(FieldError.BadPort("x")))), outer)
    }

    @Test
    fun `reading a failed value after its block returned fails with an illegal state`() {
        var escaped: Collected<Int>? = null
        val _: Result<Int, AllErrors<FieldError>> = resultAll {
            escaped = port("x").collect()
            0
        }

        val failure = assertFailsWith<IllegalStateException> { escaped!!.value }

        assertEquals("a collected value read after its resultAll block returned", failure.message)
    }

    @Test
    fun `a bind after its block returned fails with an illegal state naming bind`() {
        var escaped: (() -> Int)? = null
        val _: Result<Int, AllErrors<FieldError>> = resultAll {
            escaped = { port("x").bind() }
            0
        }

        val failure = assertFailsWith<IllegalStateException> { escaped!!() }

        assertEquals("bind() called after its resultAll block returned", failure.message)
    }

    @Test
    fun `a collect after its block returned fails with an illegal state`() {
        var escaped: (() -> Collected<Int>)? = null
        val _: Result<Int, AllErrors<FieldError>> = resultAll {
            escaped = { port("x").collect() }
            0
        }

        val failure = assertFailsWith<IllegalStateException> { escaped!!() }

        assertEquals("collect() called after its resultAll block returned", failure.message)
    }

    @Test
    fun `steps collected concurrently keep every error`() = runTest {
        val collected: Result<Int, AllErrors<FieldError>> = resultAll {
            coroutineScope {
                repeat(CONCURRENT_WORKERS) { worker ->
                    launch(Dispatchers.Default) {
                        repeat(STEPS_PER_WORKER) { step -> port("$worker-$step").collect() }
                    }
                }
            }
            0
        }

        val errors = assertIs<Result.Error<AllErrors<FieldError>>>(collected).error.errors
        assertEquals(CONCURRENT_WORKERS * STEPS_PER_WORKER, errors.toSet().size)
    }

    @Test
    fun `a collect racing the block's return from another coroutine fails the block or fails that coroutine`() =
        runTest {
            val lost = withContext(Dispatchers.Default) { (1..RACE_ROUNDS).count { !collectRacingReturnIsKept() } }

            assertEquals(0, lost, "rounds out of $RACE_ROUNDS where the block gave Ok and the collected error was lost")
        }

    private suspend fun collectRacingReturnIsKept(): Boolean {
        val foreignFailure = AtomicReference<Throwable?>(null)
        val foreign = CoroutineScope(
            SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> foreignFailure.store(e) },
        )
        val collecting = AtomicBoolean(false)
        lateinit var job: Job

        val checked: Result<Int, AllErrors<FieldError>> = resultAll {
            job = foreign.launch {
                collecting.store(true)
                port("x").collect()
            }
            // Returning the moment the collect starts puts the two in a tight race.
            while (!collecting.load()) continue
            7
        }
        job.join()

        return checked == Err(AllErrors(listOf(FieldError.BadPort("x")))) ||
            foreignFailure.load() is IllegalStateException
    }

    @Test
    fun `AllErrors holds at least one error`() {
        assertFailsWith<IllegalArgumentException> { AllErrors<FieldError>(emptyList()) }
    }
}
