package com.fromwau.kern.exec.test

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.result.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeStarterTest {
    private val ls = Command(listOf("ls"))

    private fun FakeStarter.child(command: Command = ls): Child =
        assertIs<Result.Success<Child>>(start(command)).value

    private fun Child.stopped(signal: Signal = Signal.Terminate) =
        assertEquals(Result.Success(Unit), stop(signal))

    private fun Child.drain(): List<ChildEvent> = generateSequence { next() }.toList()

    private val output = ChildEvent.Output("a\n", ChildStream.Stdout)

    @Test
    fun `a scripted run plays its events then null`() {
        val events = listOf(output, ChildEvent.Exited(0), ChildEvent.OutputClosed)
        val child = FakeStarter { FakeRun.Starts(events, pid = 7) }.child()

        assertEquals(7, child.pid)
        assertEquals(events, child.drain())
        assertNull(child.next())
    }

    @Test
    fun `a scripted failure is the start's error`() {
        val starter = FakeStarter { FakeRun.Fails(ExecError.ProgramNotFound("x")) }

        assertEquals(Result.Error(ExecError.ProgramNotFound("x")), starter.start(Command(listOf("x"))))
    }

    @Test
    fun `every command started is recorded`() {
        val starter = FakeStarter { FakeRun.Starts(emptyList()) }
        val other = Command(listOf("git", "log"))

        assertIs<Result.Success<Child>>(starter.start(ls))
        assertIs<Result.Success<Child>>(starter.start(other))

        assertEquals(listOf(ls, other), starter.started)
        assertEquals(2, starter.children.size)
    }

    @Test
    fun `a failed start is recorded too`() {
        val starter = FakeStarter { FakeRun.Fails(ExecError.EmptyCommand) }

        assertIs<Result.Error<ExecError>>(starter.start(Command(emptyList())))

        assertEquals(listOf(Command(emptyList())), starter.started)
        assertTrue(starter.children.isEmpty())
    }

    @Test
    fun `the script chooses by command`() {
        val starter = FakeStarter { command ->
            if (command.argv.first() == "ls") FakeRun.Starts(listOf(ChildEvent.Exited(0)))
            else FakeRun.Fails(ExecError.ProgramNotFound(command.argv.first()))
        }

        assertEquals(listOf<ChildEvent>(ChildEvent.Exited(0)), starter.child(ls).drain())
        assertIs<Result.Error<ExecError>>(starter.start(Command(listOf("nope"))))
    }

    @Test
    fun `a stopped fake ends with 128 plus the signal`() {
        val starter = FakeStarter { FakeRun.Starts(listOf(output, output)) }
        val child = starter.child()

        assertEquals(Result.Success(Unit), child.stop(Signal.Interrupt))

        assertEquals(listOf(output, output, ChildEvent.Exited(130), ChildEvent.OutputClosed), child.drain())
        assertEquals(listOf<Signal>(Signal.Interrupt), starter.children.single().stops)
    }

    @Test
    fun `a stop keeps the output not yet taken`() {
        val late = ChildEvent.Output("b", ChildStream.Stderr)
        val child = FakeStarter { FakeRun.Starts(listOf(output, late)) }.child()

        assertEquals(output, child.next())
        child.stopped()

        assertEquals(listOf(late, ChildEvent.Exited(143), ChildEvent.OutputClosed), child.drain())
    }

    @Test
    fun `a stop replaces the scripted end not yet taken`() {
        val script = listOf(output, ChildEvent.Exited(0), ChildEvent.OutputClosed)
        val child = FakeStarter { FakeRun.Starts(script) }.child()

        child.stopped(Signal.Kill)

        assertEquals(listOf(output, ChildEvent.Exited(137), ChildEvent.OutputClosed), child.drain())
    }

    @Test
    fun `a stop after the exit plays only the output end`() {
        val child = FakeStarter { FakeRun.Starts(listOf(ChildEvent.Exited(0), ChildEvent.OutputClosed)) }.child()

        assertEquals(ChildEvent.Exited(0), child.next())
        child.stopped(Signal.Kill)

        assertEquals(listOf<ChildEvent>(ChildEvent.OutputClosed), child.drain())
    }

    @Test
    fun `a stop of an ended child is recorded and changes nothing`() {
        val child = FakeStarter { FakeRun.Starts(listOf(ChildEvent.Exited(0), ChildEvent.OutputClosed)) }.child()
        assertEquals(listOf<ChildEvent>(ChildEvent.Exited(0), ChildEvent.OutputClosed), child.drain())

        child.stopped()

        assertNull(child.next())
    }

    @Test
    fun `a second stop does not play the end twice`() {
        val child = FakeStarter { FakeRun.Starts(listOf(output)) }.child()

        child.stopped(Signal.Interrupt)
        child.stopped(Signal.Kill)

        assertEquals(listOf(output, ChildEvent.Exited(130), ChildEvent.OutputClosed), child.drain())
    }

    @Test
    fun `a stop after failed ends nothing more`() {
        val failed = ChildEvent.Failed(ExecError.ReadFailed(ChildStream.Stdout, "boom"))
        val child = FakeStarter { FakeRun.Starts(listOf(failed, output)) }.child()

        assertEquals(failed, child.next())

        assertEquals(Result.Success(Unit), child.stop())
        assertNull(child.next())
    }

    @Test
    fun `a numbered signal is 128 plus its number`() {
        val child = FakeStarter { FakeRun.Starts(emptyList()) }.child()

        child.stopped(Signal.Number(10))

        assertEquals(ChildEvent.Exited(138), child.next())
    }

    @Test
    fun `a detached fake gives null`() {
        val starter = FakeStarter { FakeRun.Starts(listOf(output)) }
        val child = starter.child()

        child.detach()

        assertNull(child.next())
        assertTrue(starter.children.single().detached)
    }

    @Test
    fun `stop after detach is recorded and next stays null`() {
        val starter = FakeStarter { FakeRun.Starts(listOf(output)) }
        val child = starter.child()
        child.detach()

        child.stopped(Signal.Kill)

        assertEquals(listOf<Signal>(Signal.Kill), starter.children.single().stops)
        assertNull(child.next())
    }
}
