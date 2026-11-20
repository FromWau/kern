package com.fromwau.kern.exec

import com.fromwau.kern.exec.spawn.kern_exec_eventfd
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.F_GETFD
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.open
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.concurrent.waitForMultipleFutures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ObsoleteWorkersApi::class, ExperimentalForeignApi::class)
class ChildEventsTest {
    private val failed = ChildEvent.Failed(ExecError.StdinFailed("broken"))

    @Test
    fun `next gives null after Failed without waiting for the child`() {
        val events = ChildEvents(wake = -1)
        events.post(failed)
        events.post(ChildEvent.Exited(0))
        assertEquals(listOf(failed, null), events.nextTwice())
    }

    @Test
    fun `an event posted after the end is dropped`() {
        val events = ChildEvents(wake = -1)
        events.post(ChildEvent.Exited(0))
        events.end()
        events.post(failed)
        assertEquals(listOf(ChildEvent.Exited(0), null), events.nextTwice())
    }

    @Test
    fun `stop still signals the child after Failed`() {
        val child = SystemStarter.start(sh("sleep 30")).assertSuccess()
        try {
            val events = ChildEvents(wake = -1)
            events.post(failed)
            assertEquals(Ok(Unit), events.stop(child.pid, Signal.Kill))
            assertTrue(ChildEvent.Exited(137) in child.drain())
        } finally {
            val _ = child.stop(Signal.Kill)
        }
    }

    @Test
    fun `stop sends nothing after release`() {
        val child = SystemStarter.start(sh("sleep 30")).assertSuccess()
        try {
            val events = ChildEvents(wake = -1)
            events.release()
            assertEquals(Ok(Unit), events.stop(child.pid, Signal.Kill))
            assertEquals("S", stateOf(child.pid), "the child was signalled")
        } finally {
            val _ = child.stop(Signal.Kill)
            val _ = child.drain()
        }
    }

    @Test
    fun `a start failure after the start keeps the start`() {
        val events = ChildEvents(wake = -1)
        events.started(42)
        events.startFailed(Err(ExecError.StartFailed(listOf("x"), "late")))
        assertEquals(Ok(42), events.awaitStart())
    }

    @Test
    fun `a second end closes nothing`() {
        val wake = kern_exec_eventfd()
        val events = ChildEvents(wake)
        val other = open("/dev/null", O_RDONLY)
        events.end()
        // The number now names another file, as it would once something else in this process opened one.
        assertEquals(wake, dup2(other, wake))
        close(other)
        try {
            events.end()
            events.startFailed(Err(ExecError.StartFailed(listOf("x"), "late")))
            assertTrue(fcntl(wake, F_GETFD) >= 0, "the file now at $wake was closed")
        } finally {
            close(wake)
        }
    }

    /** The state letter in `/proc/<pid>/stat`, such as S for sleeping or Z for a zombie; empty when there is none. */
    private fun stateOf(pid: Int): String =
        sh("sed 's/.*) //; s/ .*//' /proc/$1/stat 2>/dev/null", pid.toString()).events().text(ChildStream.Stdout).trim()

    /** Two calls of next on another thread, ended after 2 s if they block so the test fails instead of hanging. */
    private fun ChildEvents.nextTwice(): List<ChildEvent?> {
        val worker = Worker.start()
        try {
            val future = worker.execute(TransferMode.SAFE, { this }) { listOf(it.next(), it.next()) }
            val returned = waitForMultipleFutures(listOf(future), 2000).isNotEmpty()
            if (!returned) end()
            val result = future.result
            assertTrue(returned, "next() blocked: $result")
            return result
        } finally {
            worker.requestTermination().result
        }
    }
}
