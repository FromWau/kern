package com.fromwau.kern.exec.test

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.Starter
import com.fromwau.kern.exec.coroutines.SuspendingChild
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking

/** What a [FakeStarter] does with one command. */
public sealed interface FakeRun {
    /**
     * The start succeeds and the child plays [events] in order.
     *
     * @property events what [Child.next] returns, one by one, then null.
     * @property pid the child's [Child.pid].
     */
    public data class Starts(val events: List<ChildEvent>, val pid: Int = 4242) : FakeRun

    /**
     * The start fails with [error] and no child exists.
     *
     * @property error what [Starter.start] returns.
     */
    public data class Fails(val error: ExecError.StartError) : FakeRun

    /**
     * The start succeeds and the child plays what the test hands it with [FakeChild.emit], as a running child's output
     * comes: its [Child.next] waits until there is something. Find the child in [FakeStarter.children].
     *
     * @property pid the child's [Child.pid].
     */
    public data class Live(val pid: Int = 4242) : FakeRun
}

/**
 * A [Starter] that runs no process: [script] says, for each command, what the start does. It records every command
 * and every child it made. It is safe to use from several threads.
 */
public class FakeStarter(private val script: (Command) -> FakeRun) : Starter {
    private val lock = reentrantLock()
    private val startedCommands = mutableListOf<Command>()
    private val madeChildren = mutableListOf<FakeChild>()

    /** Every command passed to [start], in order, the ones whose start failed included. */
    public val started: List<Command> get() = lock.withLock { startedCommands.toList() }

    /** The children made so far, in order. */
    public val children: List<FakeChild> get() = lock.withLock { madeChildren.toList() }

    private fun made(child: FakeChild): Result<Child, ExecError.StartError> {
        lock.withLock { madeChildren += child }
        return Result.Success(child)
    }

    override fun start(command: Command): Result<Child, ExecError.StartError> {
        lock.withLock { startedCommands += command }
        return when (val run = script(command)) {
            is FakeRun.Fails -> Result.Error(run.error)
            is FakeRun.Starts -> made(FakeChild(run.events, run.pid))
            is FakeRun.Live -> made(FakeChild(pid = run.pid, live = true))
        }
    }
}

/**
 * The [Child] a [FakeStarter] makes, or a test makes itself. It plays [script], then what [emit] adds, in order. A
 * scripted child never blocks: [next] returns null once the events are played. A [live] one waits in [next], or
 * suspends in [awaitNext], until [emit], [stop] or [detach] gives it something. Either ends after [ChildEvent.Exited] and [ChildEvent.OutputClosed]
 * were both taken, or after [ChildEvent.Failed].
 *
 * [stop] keeps the output not yet taken, as a real child's output written before the signal still comes, and replaces
 * the end not yet taken with the end of a child killed by the signal: [ChildEvent.Exited] with 128 plus the signal's
 * number, then [ChildEvent.OutputClosed]. After [detach] [next] returns null, and [stop] is still recorded. It is safe
 * to use from several threads.
 *
 * @param script the events it plays first.
 * @property pid its [Child.pid].
 * @param live whether [next] waits for [emit] once the events run out, as a running child's reader does.
 */
public class FakeChild(
    script: List<ChildEvent> = emptyList(),
    override val pid: Int = 4242,
    private val live: Boolean = false,
) : SuspendingChild {
    private val lock = reentrantLock()
    private val pending = ArrayDeque(script)
    private val signals = mutableListOf<Signal>()
    private var exitTaken = false
    private var closeTaken = false
    private var failedTaken = false
    private var stopped = false
    private var isDetached = false

    // Conflated: one waiting next needs only to know that something changed.
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The signals [stop] was called with, in order. */
    public val stops: List<Signal> get() = lock.withLock { signals.toList() }

    /** Whether [detach] was called. */
    public val detached: Boolean get() = lock.withLock { isDetached }

    override fun next(): ChildEvent? {
        while (true) {
            when (val step = step()) {
                is Step.Taken -> return step.event
                Step.Waiting -> runBlocking { wake.receive() }
            }
        }
    }

    override suspend fun awaitNext(): ChildEvent? {
        while (true) {
            when (val step = step()) {
                is Step.Taken -> return step.event
                Step.Waiting -> wake.receive()
            }
        }
    }

    private sealed interface Step {
        data class Taken(val event: ChildEvent?) : Step

        data object Waiting : Step
    }

    /** The next event, null once ended, or [Step.Waiting] while a live child has nothing yet. */
    private fun step(): Step = lock.withLock {
        when {
            isDetached || failedTaken -> Step.Taken(null)
            pending.isNotEmpty() -> Step.Taken(taken())
            !live || ended -> Step.Taken(null)
            else -> Step.Waiting
        }
    }

    private val ended: Boolean get() = failedTaken || exitTaken && closeTaken

    /** Takes the first pending event; called under the lock. */
    private fun taken(): ChildEvent {
        val event = pending.removeFirst()
        when (event) {
            is ChildEvent.Exited -> exitTaken = true
            ChildEvent.OutputClosed -> closeTaken = true
            is ChildEvent.Failed -> {
                failedTaken = true
                pending.clear()
            }
            is ChildEvent.Output -> Unit
        }
        if (exitTaken && closeTaken) pending.clear()
        return event
    }

    override fun stop(signal: Signal): EmptyResult<ExecError> = lock.withLock {
        signals += signal
        if (!stopped && !failedTaken) {
            stopped = true
            pending.removeAll { it is ChildEvent.Exited || it == ChildEvent.OutputClosed }
            if (!exitTaken) pending += ChildEvent.Exited(128 + signal.number)
            if (!closeTaken) pending += ChildEvent.OutputClosed
        }
        Result.Success(Unit)
    }.also { wake.trySend(Unit) }

    override fun detach() {
        lock.withLock { isDetached = true }
        wake.trySend(Unit)
    }

    /**
     * Adds [events] after those not yet taken, as output the child writes now, and wakes a [next] waiting for them.
     * Once the child has ended, after its exit and close were taken or after a [ChildEvent.Failed], it adds nothing.
     */
    public fun emit(vararg events: ChildEvent) {
        lock.withLock { if (!ended) pending += events }
        wake.trySend(Unit)
    }
}
