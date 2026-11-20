@file:OptIn(ExperimentalCoroutinesApi::class)

package com.fromwau.kern.exec.test

import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.exec.coroutines.awaitExit
import com.fromwau.kern.exec.coroutines.events
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VirtualTimeTest {
    private val output = ChildEvent.Output("a\n", ChildStream.Stdout)

    @Test
    fun `a live child's events suspend on a test dispatcher until the test emits`() = runTest {
        val child = FakeChild(live = true)
        val seen = mutableListOf<ChildEvent>()
        val reading = launch { child.events(StandardTestDispatcher(testScheduler)).collect { seen += it } }

        runCurrent()
        child.emit(output)
        runCurrent()
        val beforeTheEnd = seen.toList()
        child.emit(ChildEvent.Exited(0), ChildEvent.OutputClosed)
        reading.join()

        assertEquals(listOf(output), beforeTheEnd)
        assertEquals(listOf(output, ChildEvent.Exited(0), ChildEvent.OutputClosed), seen)
    }

    @Test
    fun `each line shows as it comes`() = runTest {
        val child = FakeChild(live = true)
        val seen = mutableListOf<ChildEvent>()
        val reading = launch { child.events().collect { seen += it } }

        child.emit(ChildEvent.Output("building\n", ChildStream.Stdout))
        runCurrent()
        assertEquals(listOf<ChildEvent>(ChildEvent.Output("building\n", ChildStream.Stdout)), seen)

        child.emit(ChildEvent.Exited(0), ChildEvent.OutputClosed)
        reading.join()
    }

    @Test
    fun `awaitExit on a live child suspends until the test emits the exit`() = runTest {
        val child = FakeChild(live = true)
        val exit = async { child.awaitExit(StandardTestDispatcher(testScheduler)) }

        runCurrent()
        child.emit(ChildEvent.Exited(4))

        assertEquals(Ok(4), exit.await())
    }
}
