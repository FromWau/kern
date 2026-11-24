package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.Starter
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a command wrote and how it ended, once [run] ran it to its end.
 *
 * @property stdout everything it wrote to stdout before it exited, decoded as UTF-8.
 * @property stderr everything it wrote to stderr before it exited, decoded as UTF-8.
 * @property exitCode its exit status, or 128 plus the signal number when a signal ended it. Any code is a [Finished]:
 * `grep` finding nothing exits 1, which is an answer, not a failure.
 */
public data class Finished(val stdout: String, val stderr: String, val exitCode: Int)

/**
 * Starts [command], waits for it to exit and returns what it wrote and its exit code.
 *
 * ```kotlin
 * val branch = SystemStarter.run(Command(listOf("git", "branch", "--show-current")))
 *     .map { it.stdout.trim() }
 * ```
 *
 * Nothing in the command's process group outlives the call. When the command exits, a background job it left running
 * is stopped, and output written after the exit is not kept. A job meant to outlive the call starts in a session of
 * its own with its output elsewhere (`setsid job >/dev/null 2>&1 &`), or through [Starter.start] with
 * [Command.newSession].
 *
 * Stopping sends SIGTERM to the group, then SIGKILL two seconds later, so a call can take up to two seconds longer
 * than the command. Past [timeout] the group is stopped the same way. Cancelling the call, a [ChildEvent.Failed], or a
 * throw from the child's [Child.next], stops it too without the call waiting for it; the throw reaches the caller.
 *
 * Everything the command writes is kept in memory: read a command whose output has no end with [events].
 *
 * @param timeout how long to wait for the exit; past it the group is stopped and the call answers
 * [ExecError.TimedOut]. Null waits as long as it takes.
 * @return the [Finished] command, or why it did not finish: what [Starter.start] answered, [ExecError.TimedOut], or
 * the error of a [ChildEvent.Failed] before the timeout. One during the stop after the timeout is part of that stop,
 * and the call answers [ExecError.TimedOut].
 */
public suspend fun Starter.run(command: Command, timeout: Duration? = null): Result<Finished, ExecError> {
    val child = when (val started = start(command)) {
        is Result.Success -> started.value
        is Result.Error -> return Err(started.error)
    }
    val stdout = StringBuilder()
    val stderr = StringBuilder()
    var exitCode: Int? = null
    var failure: ExecError? = null
    val timedOut = CompletableDeferred<Unit>()
    try {
        coroutineScope {
            val terminated = CompletableDeferred<Unit>()
            launch {
                terminated.await()
                delay(GRACE)
                child.killAndLetGo()
            }
            if (timeout != null) {
                launch {
                    delay(timeout)
                    timedOut.complete(Unit)
                    child.terminate(terminated)
                }
            }
            while (true) {
                val event = child.readNext() ?: break
                val keeping = exitCode == null && !timedOut.isCompleted
                when (event) {
                    is ChildEvent.Output -> if (keeping) {
                        val _ = (if (event.stream == ChildStream.Stdout) stdout else stderr).append(event.text)
                    }
                    is ChildEvent.Exited -> {
                        if (keeping) exitCode = event.code
                        // Before the next read: exec holds the pid, and so the group, until it is read past the end.
                        child.terminate(terminated)
                    }
                    is ChildEvent.Failed -> {
                        failure = event.error
                        break
                    }
                    ChildEvent.OutputClosed -> {}
                }
            }
            coroutineContext.cancelChildren()
        }
    } catch (thrown: Throwable) {
        val _ = inBackground.launch { child.stopGroup() }
        throw thrown
    }
    // Not read past the Failed, so the pid stays held while the group is stopped without the call waiting for it.
    if (failure != null) {
        val _ = inBackground.launch { child.stopGroup() }
    }
    val code = exitCode
    val failed = failure
    return when {
        code != null -> Ok(Finished(stdout.toString(), stderr.toString(), code))
        timeout != null && timedOut.isCompleted ->
            Err(ExecError.TimedOut(timeout, stdout.toString(), stderr.toString()))
        failed != null -> Err(failed)
        else -> Err(ExecError.Detached)
    }
}

/** How long the group gets after SIGTERM before SIGKILL. */
private val GRACE = 2.seconds

/** Sends SIGTERM to the group once, whether the exit or the timeout asks first. */
private fun Child.terminate(terminated: CompletableDeferred<Unit>) {
    if (terminated.complete(Unit)) {
        val _ = stop(Signal.Terminate)
    }
}

private suspend fun Child.stopGroup() {
    val _ = stop(Signal.Terminate)
    delay(GRACE)
    killAndLetGo()
}

private fun Child.killAndLetGo() {
    val _ = stop(Signal.Kill)
    // Only a process in a session of its own survives SIGKILL to the group, and waiting for it to close the output
    // gains nothing.
    detach()
}

/**
 * [Child.next], read so that cancelling the call abandons the read instead of detaching the child as [events] does:
 * the pid then stays held until the stop that follows lets go of it.
 */
private suspend fun Child.readNext(): ChildEvent? =
    if (this is SuspendingChild) awaitNext() else inBackground.async(waiting) { next() }.await()

// Outlives the call: the stop after a cancellation or a Failed, and a read a cancellation abandoned.
private val inBackground = CoroutineScope(SupervisorJob() + Dispatchers.Default)
