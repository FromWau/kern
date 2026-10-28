package com.fromwau.kern.result

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Keeps an outer [result] block's [ResultScope.bind] out of reach inside a nested one. */
@DslMarker
public annotation class ResultDsl

/** The receiver of a [result] block: where [bind] is available. */
@ResultDsl
@OptIn(ExperimentalAtomicApi::class)
public class ResultScope<E : ResultError> @PublishedApi internal constructor() {
    // One atomic state, so a bind cannot record its error after the block's outcome was read.
    private val state = AtomicReference<State>(State.Running)

    /** The value of this success; an error ends the [result] block with that error as its result. */
    @IgnorableReturnValue
    public fun <S> Result<S, E>.bind(): S {
        when (this) {
            is Result.Success -> when (state.load()) {
                State.Running -> return value
                is State.Failed -> Unit
                State.Closed -> throw closedError()
            }
            is Result.Error -> record(error)
        }
        val signal = currentBindSignal()
        signal.target = this@ResultScope
        throw signal
    }

    /** [bind] for a result of another error type, made into this block's by [convert] first. */
    @IgnorableReturnValue
    public inline fun <S, F : ResultError> Result<S, F>.bind(convert: (F) -> E): S = mapError(convert).bind()

    private fun record(error: ResultError) {
        while (true) {
            when (state.load()) {
                State.Running -> if (state.compareAndSet(State.Running, State.Failed(error))) return
                is State.Failed -> return
                State.Closed -> throw closedError()
            }
        }
    }

    private fun closedError() = IllegalStateException("bind() called after its result block returned")

    /** Ends the scope and gives the first error a bind in it met, or null when none failed or it was already closed. */
    @PublishedApi
    internal fun close(): ResultError? = (state.exchange(State.Closed) as? State.Failed)?.error

    /** The error to end this block with when [signal] reached it, or null when the signal is on its way out further. */
    @PublishedApi
    internal fun claim(signal: BindSignal): ResultError? {
        val failed = state.load() as? State.Failed ?: return null
        val target = signal.target
        // A signal resumed here after a suspension may carry a target its first thread has since moved on from.
        val outward = target !== this && target != null && !target.isClosed && isThisThreadsSignal(signal)
        return if (outward) null else failed.error
    }

    private val isClosed: Boolean get() = state.load() === State.Closed

    private sealed interface State {
        data object Running : State

        class Failed(val error: ResultError) : State

        data object Closed : State
    }
}

/**
 * Runs [block] and gives its value as a success, or the first error a `bind()` in it met, so fallible steps read
 * top to bottom:
 *
 * ```kotlin
 * fun register(form: Form): Result<User, SignupError> = result {
 *     val email = parseEmail(form.email).bind()
 *     createUser(email).bind()
 * }
 * ```
 *
 * [block] may suspend. Bind an error of another type with a conversion:
 * `validateEmail(raw).bind { SignupError.Email(it) }`. The block's value is its last expression, so bind a last
 * call that returns a result too.
 *
 * `bind` leaves the block by throwing a signal that is not an [Exception], so `catch (e: Exception)` lets it
 * through. Swallowing it with `catch (t: Throwable)` or `runCatching` cannot make the result a success, but the
 * code after it runs until the next `bind`. A failing `bind` in another coroutine that runs while the block does,
 * such as one in a nested `runBlocking` or a `launch` the block joins, still ends the block with its error. Once the
 * block has returned, `bind` throws an [IllegalStateException]. Other exceptions, cancellation included, pass
 * through.
 */
public inline fun <S, E : ResultError> result(block: ResultScope<E>.() -> S): Result<S, E> {
    val scope = ResultScope<E>()
    return try {
        val value = scope.block()
        // A failed bind whose signal the block swallowed, or that ran in another coroutine, still decides.
        @Suppress("UNCHECKED_CAST")
        scope.close()?.let { Err(it as E) } ?: Ok(value)
    } catch (signal: BindSignal) {
        @Suppress("UNCHECKED_CAST")
        Err((scope.claim(signal) ?: throw signal) as E)
    } finally {
        // Closing twice is harmless, and an exception passing through still has to close the scope.
        val _ = scope.close()
    }
}

// Not an Exception, so a `catch (e: Exception)` in the block lets it pass. One per thread, thrown by every failing bind
// on it, so a failure constructs no throwable and captures no stack.
@PublishedApi
internal class BindSignal : ReusableThrowable("bind() escaped its result block; call it in the block's own coroutine") {
    // The block the latest failing bind on this thread ends, since every block on the thread shares this signal.
    var target: ResultScope<*>? = null
}

/** A throwable thrown again and again, which keeps no stack trace or suppressed exceptions where its target allows. */
@PublishedApi
internal expect abstract class ReusableThrowable(message: String) : Throwable

/** This thread's [BindSignal]. */
internal expect fun currentBindSignal(): BindSignal

/** Whether [signal] is the one this thread throws, never replacing it as [currentBindSignal] may. */
internal expect fun isThisThreadsSignal(signal: BindSignal): Boolean
