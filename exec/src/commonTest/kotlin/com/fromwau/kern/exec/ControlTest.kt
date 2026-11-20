package com.fromwau.kern.exec

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class ControlTest {
    @Test
    fun `stop ends a running child with 143`() = withChild(sh("sleep 30")) { child ->
        assertEquals(Ok(Unit), child.stop())
        val exit = child.eventsWithin(2.seconds).filterIsInstance<ChildEvent.Exited>().single()
        assertEquals(ChildEvent.Exited(143), exit)
    }

    @Test
    fun `stop ends every process of a pipeline`() {
        withChild(sh("sleep 30 | sleep 31; wait")) { child ->
            assertTrue(eventually(2.seconds) { sleepers().isNotEmpty() }, "the pipeline did not start")
            assertEquals(Ok(Unit), child.stop())
            assertTrue(eventually(2.seconds) { sleepers().isEmpty() }, "still running: ${sleepers()}")
        }
    }

    @Test
    fun `a child started in a new session leads it and one started without stays in this one`() {
        // Field 1 of /proc/PID/stat is the pid, field 6 the session.
        val script = "set -- \$(cat /proc/\$\$/stat); echo \"\$1 \$6\""
        fun pidAndSession(newSession: Boolean): List<String> =
            Command(listOf("/bin/sh", "-c", script), newSession = newSession)
                .events()
                .text(ChildStream.Stdout)
                .trim()
                .split(' ')

        val (leader, itsSession) = pidAndSession(newSession = true)
        val (member, ourSession) = pidAndSession(newSession = false)

        assertEquals(leader, itsSession)
        assertNotEquals(member, ourSession)
    }

    @Test
    fun `stop ends every process of a pipeline started in a new session`() {
        withChild(sh("sleep 30 | sleep 31; wait").copy(newSession = true)) { child ->
            assertTrue(eventually(2.seconds) { sleepers().isNotEmpty() }, "the pipeline did not start")
            assertEquals(Ok(Unit), child.stop())
            assertTrue(eventually(2.seconds) { sleepers().isEmpty() }, "still running: ${sleepers()}")
        }
    }

    @Test
    fun `stop on a child that already ended is success`() {
        val child = SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess()
        val _ = child.drain()
        assertEquals(Ok(Unit), child.stop())
    }

    @Test
    fun `each signal ends a child with 128 plus its number`() {
        val codes = listOf(Signal.Interrupt, Signal.Hangup, Signal.Quit, Signal.Number(10)).map { signal ->
            // No core file for SIGQUIT.
            withChild(sh("ulimit -c 0; exec sleep 30")) { child ->
                assertEquals(Ok(Unit), child.stop(signal))
                child.eventsWithin(2.seconds).filterIsInstance<ChildEvent.Exited>().single().code
            }
        }
        assertEquals(listOf(130, 129, 131, 138), codes)
    }

    @Test
    fun `SIGTERM and SIGKILL by number end a child as by name`() {
        val codes = listOf(Signal.Number(15), Signal.Number(9)).map { signal ->
            withChild(Command(listOf("sleep", "30"))) { child ->
                assertEquals(Ok(Unit), child.stop(signal))
                child.eventsWithin(2.seconds).filterIsInstance<ChildEvent.Exited>().single().code
            }
        }
        assertEquals(listOf(143, 137), codes)
    }

    @Test
    fun `stop reaches a background job after the child exited`() {
        withChild(sh("sleep 30 & exit 0")) { child ->
            assertEquals(ChildEvent.Exited(0), child.next())
            assertEquals(Ok(Unit), child.stop())
            assertEquals(listOf(ChildEvent.OutputClosed), child.eventsWithin(2.seconds))
        }
    }

    @Test
    fun `stop reaches a job that redirected its output until the events are read to the end`() {
        val child = SystemStarter.start(sh("sleep 32 >/dev/null 2>&1 & exit 0")).assertSuccess()
        val taken = listOf(child.next(), child.next()).toSet()
        assertEquals(setOf(ChildEvent.Exited(0), ChildEvent.OutputClosed), taken)
        assertTrue(eventually(2.seconds) { redirectedJob().isNotEmpty() }, "the job did not start")

        try {
            assertEquals(Ok(Unit), child.stop())
            assertTrue(eventually(2.seconds) { redirectedJob().isEmpty() }, "still running: ${redirectedJob()}")
            assertEquals(null, child.next())
        } finally {
            val _ = Command(listOf("pkill", "-f", "^sleep 32$")).events()
        }
    }

    @Test
    fun `an exited child keeps its pid while its output is open`() {
        withChild(sh("sleep 30 & exit 0")) { child ->
            assertEquals(ChildEvent.Exited(0), child.next())
            // Held, so the pid cannot go to another process group that stop would then signal.
            assertTrue(SystemFileSystem.exists(Path("/proc/${child.pid}")), "the child was reaped with its output open")
            assertEquals(Ok(Unit), child.stop(Signal.Kill))
            assertEquals(listOf(ChildEvent.OutputClosed), child.eventsWithin(2.seconds))
            assertTrue(eventually(2.seconds) { !SystemFileSystem.exists(Path("/proc/${child.pid}")) }, "not reaped")
        }
    }

    @Test
    fun `detach wakes a next that is blocked`() = withChild(sh("sleep 2")) { child ->
        val next = inBackground { listOf(child.next()) }
        assertTrue(ChildEvent.Exited(0) in sh("sleep 0.2").events())
        child.detach()
        assertEquals(listOf(null), next(1.seconds))
    }

    @Test
    fun `a detached child writing much still finishes`() {
        val folder = temporaryFolder()
        try {
            val marker = "$folder/marker"
            val command = sh("head -c 10485760 /dev/zero; echo done > \"$1\"", marker)
            val child = SystemStarter.start(command).assertSuccess()
            child.detach()
            assertTrue(eventually(10.seconds) { SystemFileSystem.exists(Path(marker)) }, "no marker within 10 s")
            assertTrue(eventually(2.seconds) { !SystemFileSystem.exists(Path("/proc/${child.pid}")) }, "not reaped")
        } finally {
            folder.removeAll()
        }
    }

    @Test
    fun `next after detach is null`() = withChild(sh("echo hi; sleep 2")) { child ->
        child.detach()
        assertEquals(null, child.next())
    }

    @Test
    fun `stop after detach ends the child`() = withChild(Command(listOf("sleep", "5"))) { child ->
        child.detach()
        assertTrue(SystemFileSystem.exists(Path("/proc/${child.pid}")), "the child ended before stop")
        assertEquals(Ok(Unit), child.stop())
        assertTrue(eventually(2.seconds) { !SystemFileSystem.exists(Path("/proc/${child.pid}")) }, "still running")
    }

    @Test
    fun `detach leaves the child running`() = withChild(Command(listOf("sleep", "5"))) { child ->
        child.detach()
        val _ = sh("sleep 0.3").events()
        assertTrue(SystemFileSystem.exists(Path("/proc/${child.pid}")), "detach ended the child")
    }

    @Test
    fun `a child is reaped once its events are read to the end or it is detached`() {
        val read = SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess()
        val _ = read.drain()
        val detached = SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess()
        detached.detach()

        for (pid in listOf(read.pid, detached.pid)) {
            assertTrue(eventually(2.seconds) { !SystemFileSystem.exists(Path("/proc/$pid")) }, "$pid is still there")
        }
    }

    /**
     * Runs [test] on a started [command], then kills what is left of the child's process group through [Child.stop],
     * which signals nothing once the child was reaped.
     */
    private fun <T> withChild(command: Command, test: (Child) -> T): T {
        val child = SystemStarter.start(command).assertSuccess()
        try {
            return test(child)
        } finally {
            val _ = child.stop(Signal.Kill)
        }
    }

    /** The events left to come, failing the test when they have not all come within [timeout]. */
    private fun Child.eventsWithin(timeout: Duration): List<ChildEvent> {
        val events = inBackground { drain() }(timeout)
        assertTrue(events != null, "the events did not end within $timeout")
        return events
    }

    /** The pids of the pipeline's two sleeps that are still running. */
    private fun redirectedJob(): String = Command(listOf("pgrep", "-f", "^sleep 32$")).events().text(ChildStream.Stdout)

    // Anchored: a shell whose own arguments quote these command lines would match them otherwise.
    private fun sleepers(): String =
        Command(listOf("pgrep", "-f", "^sleep 3[01]$")).events().text(ChildStream.Stdout)

    /** Whether [condition] holds within [timeout], checked every 50 ms. */
    private fun eventually(timeout: Duration, condition: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (started.elapsedNow() > timeout) return false
            val _ = sh("sleep 0.05").events()
        }
        return true
    }
}
