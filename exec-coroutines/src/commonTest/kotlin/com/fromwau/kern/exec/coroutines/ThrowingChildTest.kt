@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.Starter
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A child whose reads throw [bug], as a bug in a [Child] would; it records what is done to it. */
private open class ThrowingChild(private val bug: Throwable) : Child {
    val stops = AtomicReference(emptyList<Signal>())
    val detached = AtomicBoolean(false)

    override val pid: Int = 4242

    override fun next(): ChildEvent? = throw bug

    override fun stop(signal: Signal): EmptyResult<ExecError> {
        while (true) {
            val before = stops.load()
            if (stops.compareAndSet(before, before + signal)) return Ok(Unit)
        }
    }

    override fun detach() = detached.store(true)
}

private class ThrowingSuspendingChild(private val bug: Throwable) : ThrowingChild(bug), SuspendingChild {
    override suspend fun awaitNext(): ChildEvent? = throw bug
}

/**
 * A child whose reads answer [read], or wait for [detach] where it answers null; [detach] throws [again] and counts
 * its calls in [detaches].
 */
internal class DetachThrowingChild(private val again: Throwable, private val read: suspend () -> ChildEvent?) :
    SuspendingChild {
    private val waiting = CompletableDeferred<ChildEvent?>()

    val detaches = AtomicInt(0)

    override val pid: Int = 4243

    override fun next(): ChildEvent? = runBlocking { awaitNext() }

    override suspend fun awaitNext(): ChildEvent? = read() ?: waiting.await()

    override fun stop(signal: Signal): EmptyResult<ExecError> = Ok(Unit)

    override fun detach() {
        val _ = detaches.incrementAndFetch()
        waiting.complete(null)
        throw again
    }
}

class ThrowingChildTest {
    private val bug = IllegalStateException("a bug in the child")

    @Test
    fun `a throw from the child reaches the caller of run unchanged and its group is stopped`() = runBlocking {
        val child = ThrowingChild(bug)

        assertSame(bug, original(assertFails { Starter { Ok(child) }.run(sh("anything")) }))
        assertTrue(
            waitFor(4.seconds) { child.stops.load() == listOf(Signal.Terminate, Signal.Kill) },
            "stops: ${child.stops.load()}",
        )
    }

    @Test
    fun `a throw from a blocking child reaches the caller of awaitExit unchanged and detaches it`() = runBlocking {
        val child = ThrowingChild(bug)

        assertSame(bug, original(assertFails { child.awaitExit() }))
        assertEquals(true to emptyList<Signal>(), child.detached.load() to child.stops.load())
    }

    @Test
    fun `a throw from a suspending child reaches the caller of awaitExit unchanged and detaches it`() = runBlocking {
        val child = ThrowingSuspendingChild(bug)

        assertSame(bug, original(assertFails { child.awaitExit() }))
        assertEquals(true to emptyList<Signal>(), child.detached.load() to child.stops.load())
    }

    @Test
    fun `a throw from detach after a throw from the child is added to the child's as suppressed`() = runBlocking {
        val again = IllegalStateException("a bug in detach")
        val child = DetachThrowingChild(again) { throw bug }

        assertSame(bug, original(assertFails { child.awaitExit() }))
        assertEquals(listOf<Throwable>(again), bug.suppressedExceptions)
    }

    @Test
    fun `a throw from detach leaves a cancelled awaitExit cancelled`() = runBlocking {
        val child = DetachThrowingChild(bug) { null }

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { child.awaitExit() })
    }

    @Test
    fun `a throw from detach leaves a cancelled awaitExit of a blocking child cancelled`() = runBlocking {
        val child = DetachThrowingChild(bug) { null }
        // Only the Child interface, so awaitExit reads it with the blocking next.
        val blocking = object : Child by child {}

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { blocking.awaitExit() })
    }

    @Test
    fun `a cancelled awaitExit of a blocking child detaches it once`() = runBlocking {
        val child = DetachThrowingChild(bug) { null }
        val blocking = object : Child by child {}

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { blocking.awaitExit() })
        assertEquals(1, child.detaches.load())
    }

    @Test
    fun `a cancelled collection of a blocking child's events detaches it once`() = runBlocking {
        val child = DetachThrowingChild(bug) { null }
        val blocking = object : Child by child {}

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { blocking.events().collect {} })
        assertEquals(1, child.detaches.load())
    }

    @Test
    fun `a throw from detach leaves a cancelled collection of events cancelled`() = runBlocking {
        val child = DetachThrowingChild(bug) { null }

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { child.events().collect {} })
    }

    /** [thrown], or the throw it copies: with assertions on, coroutines rethrow a copy that holds the original. */
    private fun original(thrown: Throwable): Throwable = if (thrown !== bug && thrown.cause === bug) bug else thrown

    private suspend fun waitFor(within: Duration, condition: () -> Boolean): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + within
        while (!condition()) {
            if (deadline.hasPassedNow()) return false
            delay(50.milliseconds)
        }
        return true
    }
}
