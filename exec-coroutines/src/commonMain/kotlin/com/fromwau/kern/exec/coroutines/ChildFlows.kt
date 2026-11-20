package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The events of this child as a cold [Flow], in the order [Child.next] gives them. The flow ends when nothing is left
 * to come.
 *
 * When the collection ends before the events ran out, by cancellation, an exception or an operator such as `first` or
 * `takeWhile`, the child is detached with [Child.detach]. It is never stopped, and [Child.stop] still reaches it. The
 * flow is meant to be collected once: a second collector takes the events the first one has not taken.
 *
 * @param dispatcher where each blocking [Child.next] runs. By default threads of `Dispatchers.IO` not bound by its
 * thread limit, so quiet children never starve the app's own IO. A [SuspendingChild] is awaited instead, and this
 * goes unused.
 */
public fun Child.events(dispatcher: CoroutineDispatcher = waiting): Flow<ChildEvent> = flow {
    var ranOut = false
    try {
        while (true) emit(nextEvent(dispatcher) ?: break)
        ranOut = true
    } finally {
        if (!ranOut) detach()
    }
}

/**
 * Waits for this child to exit and returns its exit code, dropping its output. A [ChildEvent.Failed] returns its
 * error. When nothing is left to read without an exit, it returns [ExecError.Detached]: the child was detached, or its
 * events were already read to the end, after a [ChildEvent.Failed] or by an earlier call.
 *
 * It returns at [ChildEvent.Exited] and leaves the child attached, so what a background job the child left running
 * writes later is kept for [Child.next]. Call [Child.detach] afterwards when you will not read it.
 *
 * Cancelling the call detaches the child with [Child.detach]. It never stops it, and [Child.stop] still reaches it, so
 * a wait that timed out can end in a kill:
 *
 * ```kotlin
 * withTimeoutOrNull(5.seconds) { child.awaitExit() } ?: child.stop(Signal.Kill)
 * ```
 *
 * @param dispatcher where each blocking [Child.next] runs, as for [events].
 */
public suspend fun Child.awaitExit(dispatcher: CoroutineDispatcher = waiting): Result<Int, ExecError> {
    try {
        while (true) {
            when (val event = nextEvent(dispatcher) ?: return Err(ExecError.Detached)) {
                is ChildEvent.Exited -> return Ok(event.code)
                is ChildEvent.Failed -> return Err(event.error)
                is ChildEvent.Output, ChildEvent.OutputClosed -> {}
            }
        }
    } catch (cancelled: CancellationException) {
        detach()
        throw cancelled
    }
}

// A view of Dispatchers.IO is not bound by its thread limit, so quiet children never starve the app's own IO.
internal val waiting: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)

/**
 * A [Child] that can wait for its next event by suspending instead of blocking a thread, such as a test's fake child.
 * [events] and [awaitExit] await it on the caller's context and leave their dispatcher unused, so `runTest` with a
 * test dispatcher drives it in virtual time.
 */
public interface SuspendingChild : Child {
    /** What [Child.next] returns, suspending instead of blocking while nothing has come. */
    public suspend fun awaitNext(): ChildEvent?
}

private suspend fun Child.nextEvent(dispatcher: CoroutineDispatcher): ChildEvent? =
    if (this is SuspendingChild) awaitNext() else blockingNext(dispatcher)

private suspend fun Child.blockingNext(dispatcher: CoroutineDispatcher): ChildEvent? = coroutineScope {
    val read = async(dispatcher) { next() }
    try {
        read.await()
    } catch (cancelled: CancellationException) {
        detach()
        throw cancelled
    }
}
