package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private suspend fun exists(path: Path, within: Duration): Boolean {
    var waited = 0L
    while (waited <= within.inWholeMilliseconds) {
        if (SystemFileSystem.exists(path)) return true
        delay(50)
        waited += 50
    }
    return false
}

private suspend fun gone(path: Path, within: Duration): Boolean {
    var waited = 0L
    while (waited <= within.inWholeMilliseconds) {
        if (!SystemFileSystem.exists(path)) return true
        delay(50)
        waited += 50
    }
    return false
}

class ChildFlowsTest {
    @Test
    fun `events gives what next gives`() = runBlocking {
        withTimeout(10.seconds) {
            val child = SystemStarter.start(Command(listOf("/bin/echo", "hi"))).assertSuccess()
            val events = child.events().toList()
            assertTrue(ChildEvent.Output("hi\n", ChildStream.Stdout) in events, "$events")
            assertTrue(ChildEvent.Exited(0) in events, "$events")
            assertTrue(ChildEvent.OutputClosed in events, "$events")
            assertEquals(3, events.size, "$events")
        }
    }

    @Test
    fun `awaitExit answers the code`() = runBlocking {
        withTimeout(10.seconds) {
            val child = SystemStarter.start(sh("exit 7")).assertSuccess()
            assertEquals(Ok(7), child.awaitExit())
        }
    }

    @Test
    fun `cancelling the collector detaches and does not stop`() = runBlocking {
        withTimeout(10.seconds) {
            val marker = markerPath()
            val child = SystemStarter.start(sh("sleep 1; echo x > '$marker'")).assertSuccess()
            val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { child.events().toList() }
            delay(100)
            job.cancelAndJoin()
            assertTrue(exists(marker, 3.seconds), "the child was stopped")
            assertEquals(null, child.next())
            SystemFileSystem.delete(marker, mustExist = false)
        }
    }

    @Test
    fun `an early stop of the collection detaches and does not stop`() = runBlocking {
        withTimeout(10.seconds) {
            val marker = markerPath()
            val child = SystemStarter.start(sh("echo a; sleep 1; echo x > '$marker'")).assertSuccess()
            child.events().takeWhile { false }.toList()
            assertEquals(null, child.next())
            assertTrue(exists(marker, 3.seconds), "the child was stopped")
            SystemFileSystem.delete(marker, mustExist = false)
        }
    }

    @Test
    fun `first detaches and does not stop`() = runBlocking {
        withTimeout(10.seconds) {
            val marker = markerPath()
            val child = SystemStarter.start(sh("echo a; sleep 1; echo x > '$marker'")).assertSuccess()
            child.events().first()
            assertEquals(null, child.next())
            assertTrue(exists(marker, 3.seconds), "the child was stopped")
            SystemFileSystem.delete(marker, mustExist = false)
        }
    }

    @Test
    fun `cancelling while the collect body is suspended detaches and does not stop`() = runBlocking {
        withTimeout(10.seconds) {
            val marker = markerPath()
            val child = SystemStarter.start(sh("echo a; sleep 1; echo x > '$marker'")).assertSuccess()
            val job = launch(Dispatchers.Default) { child.events().collect { delay(5000) } }
            delay(300)
            job.cancelAndJoin()
            assertEquals(null, child.next())
            assertTrue(exists(marker, 3.seconds), "the child was stopped")
            SystemFileSystem.delete(marker, mustExist = false)
        }
    }

    @Test
    fun `cancelling awaitExit detaches`() = runBlocking {
        withTimeout(10.seconds) {
            val marker = markerPath()
            val child = SystemStarter.start(sh("sleep 1; echo x > '$marker'")).assertSuccess()
            var answered: Any? = null
            val job = launch(Dispatchers.Default) { answered = child.awaitExit() }
            delay(100)
            job.cancelAndJoin()
            assertEquals(null, answered)
            assertEquals(null, child.next())
            assertTrue(exists(marker, 3.seconds), "the child was stopped")
            SystemFileSystem.delete(marker, mustExist = false)
        }
    }

    @Test
    fun `a child that outlasts a timeout on awaitExit can still be killed`() = runBlocking {
        withTimeout(10.seconds) {
            val child = SystemStarter.start(Command(listOf("sleep", "5"))).assertSuccess()
            val exit = withTimeoutOrNull(300.milliseconds) { child.awaitExit() } ?: child.stop(Signal.Kill)
            assertEquals(Ok(Unit), exit)
            assertTrue(gone(Path("/proc/${child.pid}"), 2.seconds), "the child still runs")
        }
    }

    @Test
    fun `awaitExit on a detached child is Detached`() = runBlocking {
        withTimeout(10.seconds) {
            val child = SystemStarter.start(sh("sleep 0.2")).assertSuccess()
            child.detach()
            assertEquals(Err(ExecError.Detached), child.awaitExit())
        }
    }

    @Test
    fun `many quiet children leave Dispatchers IO free`() = runBlocking {
        withTimeout(20.seconds) {
            val children = List(QUIET_CHILDREN) { SystemStarter.start(sh("sleep 5")).assertSuccess() }
            try {
                val waits = children.map { child -> launch(Dispatchers.Default) { val _ = child.awaitExit() } }
                delay(500)
                val ran = withTimeoutOrNull(2.seconds) { withContext(Dispatchers.IO) { true } }
                assertEquals(true, ran, "Dispatchers.IO ran nothing while $QUIET_CHILDREN children were awaited")
                children.forEach { val _ = it.stop(Signal.Kill) }
                waits.joinAll()
            } finally {
                children.forEach { val _ = it.stop(Signal.Kill) }
            }
        }
    }

    private companion object {
        // Past the 64 threads Dispatchers.IO allows on a machine with at most 64 cores.
        const val QUIET_CHILDREN = 80
    }
}
