package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.exec.test.FakeRun
import com.fromwau.kern.exec.test.FakeStarter
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class RunTest {
    @Test
    fun `run gives both streams and the exit code and a non-zero exit is Ok`() = runBlocking {
        assertEquals(
            Ok(Finished(stdout = "out\n", stderr = "err\n", exitCode = 3)),
            SystemStarter.run(sh("echo out; echo err >&2; exit 3")),
        )
    }

    @Test
    fun `a command that cannot start is its start error`() = runBlocking {
        assertEquals(
            Err(ExecError.ProgramNotFound("kern-no-such-program")),
            SystemStarter.run(Command(listOf("kern-no-such-program"))),
        )
    }

    @Test
    fun `run returns at the exit and stops a background job the command left`() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val finished = SystemStarter.run(sh("echo now; (sleep 37.1; echo late) & exit 0"))
        val took = started.elapsedNow()

        assertEquals(Ok(Finished(stdout = "now\n", stderr = "", exitCode = 0)), finished)
        assertTrue(took < 3.seconds, "took $took")
        assertTrue(gone("sleep 37.1", within = 2.seconds), "the background job still runs")
    }

    @Test
    fun `a job that ignores SIGTERM is killed after the grace`() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val finished = SystemStarter.run(sh("trap '' TERM; sleep 37.2 & echo up; exit 0"))
        val took = started.elapsedNow()

        assertEquals(Ok(Finished(stdout = "up\n", stderr = "", exitCode = 0)), finished)
        assertTrue(took >= 2.seconds && took < 4.seconds, "took $took")
        assertTrue(gone("sleep 37.2", within = 1.seconds), "the job that ignores SIGTERM still runs")
    }

    @Test
    fun `past its timeout a command is stopped and answers what both streams got`() = runBlocking {
        val finished = SystemStarter.run(sh("echo partial; echo warn >&2; sleep 37.3"), timeout = 1.seconds)

        assertEquals(Err(ExecError.TimedOut(1.seconds, stdout = "partial\n", stderr = "warn\n")), finished)
        assertTrue(gone("sleep 37.3", within = 2.seconds), "the command still runs")
    }

    @Test
    fun `cancelling run stops the command`() = runBlocking {
        val running = launch(Dispatchers.Default) { val _ = SystemStarter.run(sh("sleep 37.4")) }
        assertTrue(appears("sleep 37.4", within = 2.seconds), "the command did not start")

        withTimeout(2.seconds) { running.cancelAndJoin() }

        assertTrue(gone("sleep 37.4", within = 4.seconds), "the command still runs")
    }

    @Test
    fun `a fake child past its timeout is stopped in virtual time`() = runTest {
        val starter = FakeStarter { FakeRun.Live() }
        val finished = async { starter.run(sh("anything"), timeout = 5.seconds) }

        delay(1.seconds)
        starter.children.single().emit(ChildEvent.Output("partial\n", ChildStream.Stdout))

        assertEquals(Err(ExecError.TimedOut(5.seconds, stdout = "partial\n", stderr = "")), finished.await())
        assertEquals(listOf<Signal>(Signal.Terminate), starter.children.single().stops)
    }

    @Test
    fun `a child that fails is stopped with SIGTERM and then SIGKILL`() = runBlocking {
        val starter = FakeStarter { FakeRun.Live() }
        val finished = async(Dispatchers.Default) { starter.run(sh("anything")) }
        while (starter.children.isEmpty()) delay(10.milliseconds)
        starter.children.single().emit(ChildEvent.Failed(ExecError.StdinFailed("broken")))

        assertEquals(Err(ExecError.StdinFailed("broken")), finished.await())
        assertTrue(
            waitFor(4.seconds) { starter.children.single().stops == listOf(Signal.Terminate, Signal.Kill) },
            "stops: ${starter.children.single().stops}",
        )
    }

    @Test
    fun `a background job that redirected its output is stopped too`() = runBlocking {
        val survived = (1..5).count { round ->
            val line = "sleep 37.7$round"
            val _ = SystemStarter.run(sh("$line >/dev/null 2>&1 & exit 0"))
            delay(300.milliseconds)
            running(line).also { val _ = SystemStarter.run(Command(listOf("pkill", "-f", anchored(line)))) }
        }
        assertEquals(0, survived)
    }

    @Test
    fun `a job in a session of its own that holds the output lets the call go after the grace`() = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        // The pause lets the job reach its own session before the command exits and its group is stopped.
        val finished = SystemStarter.run(sh("setsid sleep 37.8 & sleep 0.2; echo up; exit 0"))
        val took = started.elapsedNow()
        val _ = SystemStarter.run(Command(listOf("pkill", "-f", anchored("sleep 37.8"))))

        assertEquals(Ok(Finished(stdout = "up\n", stderr = "", exitCode = 0)), finished)
        assertTrue(took < 3.seconds, "took $took")
    }

    /** Whether no process runs exactly [commandLine] within [within]. */
    private suspend fun gone(commandLine: String, within: Duration): Boolean =
        waitFor(within) { !running(commandLine) }

    private suspend fun appears(commandLine: String, within: Duration): Boolean =
        waitFor(within) { running(commandLine) }

    private suspend fun waitFor(within: Duration, condition: () -> Boolean): Boolean {
        val started = TimeSource.Monotonic.markNow()
        while (!condition()) {
            if (started.elapsedNow() > within) return false
            delay(50.milliseconds)
        }
        return true
    }

    // Anchored: a shell whose own arguments quote the command line would match it otherwise.
    private fun anchored(commandLine: String): String = "^" + commandLine.replace(".", "\\.") + "$"

    private fun running(commandLine: String): Boolean {
        val child = (SystemStarter.start(Command(listOf("pgrep", "-f", anchored(commandLine)))) as Ok).value
        return generateSequence { child.next() }.any { it is ChildEvent.Exited && it.code == 0 }
    }
}
