package com.fromwau.kern.exec.test

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.result.Result
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class LiveFakeTest {
    private val output = ChildEvent.Output("a\n", ChildStream.Stdout)

    /** Starts a read of [child] on another thread and checks it is still waiting a while later. */
    private suspend fun kotlinx.coroutines.CoroutineScope.waitingRead(child: Child): Deferred<ChildEvent?> {
        val read = async(Dispatchers.Default) { child.next() }
        delay(100.milliseconds)
        assertTrue(read.isActive, "next did not wait: ${if (read.isCompleted) read.await() else null}")
        return read
    }

    @Test
    fun `a live child's next waits for what the test emits and ends after the exit and the close`() = runBlocking {
        val starter = FakeStarter { FakeRun.Live() }
        val child = assertIs<Result.Success<Child>>(starter.start(Command(listOf("ls")))).value

        val read = waitingRead(child)
        starter.children.single().emit(output)
        assertEquals(output, withTimeout(2.seconds) { read.await() })

        starter.children.single().emit(ChildEvent.Exited(0), ChildEvent.OutputClosed)
        assertEquals(listOf(ChildEvent.Exited(0), ChildEvent.OutputClosed, null), List(3) { child.next() })
    }

    @Test
    fun `a stop wakes a waiting live child with the end of one the signal killed`() = runBlocking {
        val child = FakeChild(live = true)
        val read = waitingRead(child)

        child.stop(Signal.Terminate).let { assertEquals(Result.Success(Unit), it) }

        assertEquals(ChildEvent.Exited(143), withTimeout(2.seconds) { read.await() })
        assertEquals(listOf(ChildEvent.OutputClosed, null), List(2) { child.next() })
    }

    @Test
    fun `a detach wakes a waiting live child with null`() = runBlocking {
        val child = FakeChild(live = true)
        val read = waitingRead(child)

        child.detach()

        assertEquals(null, withTimeout(2.seconds) { read.await() })
    }

    @Test
    fun `a scripted child built directly plays its events and what is emitted before its end`() {
        val child = FakeChild(listOf(output), pid = 7)
        child.emit(ChildEvent.Exited(0), ChildEvent.OutputClosed)

        assertEquals(7, child.pid)
        assertEquals(listOf(output, ChildEvent.Exited(0), ChildEvent.OutputClosed, null), List(4) { child.next() })
    }
}
