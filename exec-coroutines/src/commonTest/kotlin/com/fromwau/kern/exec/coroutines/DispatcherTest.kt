package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

class DispatcherTest {
    private val script =
        listOf(ChildEvent.Output("hi\n", ChildStream.Stdout), ChildEvent.Exited(0), ChildEvent.OutputClosed)

    @Test
    fun `events and awaitExit read on the dispatcher they are given`() = runTest {
        val dispatcher = Counting(StandardTestDispatcher(testScheduler))

        val events = Scripted(script).events(dispatcher).toList()
        val forEvents = dispatcher.dispatched
        val code = Scripted(script).awaitExit(dispatcher)

        assertEquals(script, events)
        assertEquals(Ok(0), code)
        // One read per event and the null after them; awaitExit reads up to the exit.
        assertEquals(listOf(4, 2), listOf(forEvents, dispatcher.dispatched - forEvents))
    }

    /** A child whose [next] hands out [events] without blocking, as a test's fake does. */
    private class Scripted(events: List<ChildEvent>) : Child {
        private val left = ArrayDeque(events)
        override val pid: Int = 1

        override fun next(): ChildEvent? = left.removeFirstOrNull()

        override fun stop(signal: Signal): EmptyResult<ExecError> = Ok(Unit)

        override fun detach() = Unit
    }

    /** [inner], counting what is dispatched through it. */
    private class Counting(private val inner: CoroutineDispatcher) : CoroutineDispatcher() {
        var dispatched = 0

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            inner.dispatch(context, block)
        }
    }
}
