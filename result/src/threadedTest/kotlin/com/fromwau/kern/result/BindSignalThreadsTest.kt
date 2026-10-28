package com.fromwau.kern.result

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotSame

private data class Absent(val id: Long) : ResultError

private data object Empty : ResultError

private const val ROUNDS = 5000

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class BindSignalThreadsTest {
    private fun find(id: Long): Result<String, Absent> = Err(Absent(id))

    private fun signalOnThisThread(): Throwable {
        lateinit var signal: Throwable
        val _: Result<String, Absent> = result {
            signal = assertFails { find(1).bind() }
            "swallowed"
        }
        return signal
    }

    @Test
    fun `each thread throws its own signal`() = runTest {
        val here = signalOnThisThread()

        val there = newSingleThreadContext("bind").use { withContext(it) { signalOnThisThread() } }

        assertNotSame(here, there)
    }

    @Test
    fun `nested blocks on many threads at once each end with their own error`() = runTest {
        val misrouted = withContext(Dispatchers.Default) {
            (1..8L).map { worker ->
                async { (1..ROUNDS).count { round -> !nestedBlocksRoute(worker * ROUNDS + round) } }
            }.awaitAll().sum()
        }

        assertEquals(0, misrouted, "rounds where a block ended with another block's error")
    }

    private fun nestedBlocksRoute(id: Long): Boolean {
        var inner: Result<Int, Empty>? = null
        val outer: Result<String, Absent> = result {
            val scope = this
            inner = result {
                val _ = runCatching { Err(Empty).bind() }
                with(scope) { find(id).bind() }.length
            }
            "never"
        }
        val alone: Result<Int, Empty> = result { Err(Empty).bind() }
        return outer == Err(Absent(id)) && inner == null && alone == Err(Empty)
    }

    @Test
    fun `a bind failing in a child coroutine on another thread ends the block with its error`() = runTest {
        val saved: Result<Int, Absent> = newSingleThreadContext("child").use { child ->
            result {
                coroutineScope { launch(child) { find(2).bind() } }
                7
            }
        }

        assertEquals(Err(Absent(2)), saved)
    }

    @Test
    fun `a block whose unwinding resumes on another thread ends with its error`() = runTest {
        val test = this
        val release = CompletableDeferred<Unit>()
        val other = CompletableDeferred<Result<Int, Empty>>()

        val saved: Result<String, Absent> = newSingleThreadContext("resumer").use { resumer ->
            withContext(Dispatchers.Unconfined) {
                result {
                    try {
                        find(2).bind()
                    } finally {
                        suspendCoroutine { unwinding ->
                            // A block on this thread fails and stays open before the unwinding moves on.
                            test.launch {
                                other.complete(
                                    result {
                                        val _ = runCatching { Err(Empty).bind() }
                                        CoroutineScope(resumer).launch { unwinding.resume(Unit) }
                                        release.await()
                                        0
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        release.complete(Unit)

        assertEquals(Err(Absent(2)), saved)
        assertEquals(Err(Empty), other.await())
    }
}
