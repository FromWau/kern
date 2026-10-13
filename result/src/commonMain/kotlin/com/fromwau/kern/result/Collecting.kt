package com.fromwau.kern.result

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.reflect.KProperty
import kotlinx.serialization.Serializable

/**
 * Every error a [resultAll] block collected, in the order its steps ran. Never empty: building one from an empty
 * list throws an [IllegalArgumentException], and so does decoding one.
 */
@Serializable(with = AllErrorsSerializer::class)
public data class AllErrors<out E : ResultError>(val errors: List<E>) : ResultError {
    init {
        require(errors.isNotEmpty()) { "AllErrors holds at least one error" }
    }
}

/**
 * A step a [resultAll] block collected: its value, read with [value] or a `by` delegate once every step has run.
 * Reading it when the step failed ends the block with every error collected so far.
 */
public class Collected<out S> internal constructor(
    private val scope: CollectScope<*>,
    private val found: S?,
    private val failed: Boolean,
) {
    public val value: S
        get() {
            if (!failed) {
                @Suppress("UNCHECKED_CAST")
                return found as S
            }
            check(scope.isOpen()) { "a collected value read after its resultAll block returned" }
            throw CollectSignal(scope)
        }

    public operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): S = value
}

/** The receiver of a [resultAll] block: where [collect] and [bind] are available. */
@ResultDsl
@OptIn(ExperimentalAtomicApi::class)
public class CollectScope<E : ResultError> @PublishedApi internal constructor() {
    // One atomic state, so a step cannot record after the block's outcome was read, nor lose a concurrent error.
    private val state = AtomicReference<State<E>>(State.Running(emptyList()))

    /** Records this step's error, if any, and hands back its value to read once every step has run. */
    @IgnorableReturnValue
    public fun <S> Result<S, E>.collect(): Collected<S> = when (this) {
        is Result.Success -> {
            checkOpen("collect()")
            Collected(this@CollectScope, value, failed = false)
        }
        is Result.Error -> {
            record(error, "collect()")
            Collected(this@CollectScope, null, failed = true)
        }
    }

    /** The value of this success, or, for an error, the end of the block with every error so far and this one. */
    @IgnorableReturnValue
    public fun <S> Result<S, E>.bind(): S = when (this) {
        is Result.Success -> {
            checkOpen("bind()")
            value
        }
        is Result.Error -> {
            record(error, "bind()")
            throw CollectSignal(this@CollectScope)
        }
    }

    /** [collect] for a result of another error type, made into this block's by [convert] first. */
    @IgnorableReturnValue
    public inline fun <S, F : ResultError> Result<S, F>.collect(convert: (F) -> E): Collected<S> =
        mapError(convert).collect()

    /** [bind] for a result of another error type, made into this block's by [convert] first. */
    @IgnorableReturnValue
    public inline fun <S, F : ResultError> Result<S, F>.bind(convert: (F) -> E): S = mapError(convert).bind()

    private fun record(
        error: E,
        caller: String,
    ) {
        while (true) {
            val now = state.load()
            check(now is State.Running) { closedMessage(caller) }
            if (state.compareAndSet(now, State.Running(now.errors + error))) return
        }
    }

    private fun checkOpen(caller: String) {
        check(isOpen()) { closedMessage(caller) }
    }

    private fun closedMessage(caller: String) = "$caller called after its resultAll block returned"

    internal fun isOpen(): Boolean = state.load() is State.Running

    /** Ends the scope and gives the errors it collected, or null when every step succeeded or it was already closed. */
    @PublishedApi
    internal fun close(): AllErrors<E>? {
        val errors = (state.exchange(State.Closed) as? State.Running)?.errors
        return if (errors.isNullOrEmpty()) null else AllErrors(errors)
    }

    private sealed interface State<out E> {
        class Running<E>(val errors: List<E>) : State<E>

        data object Closed : State<Nothing>
    }
}

/**
 * Runs [block], where each step is collected rather than ending the block at its first error, and gives the
 * block's value when every step succeeded, or [AllErrors] with every error in the order the steps ran:
 *
 * ```kotlin
 * fun validate(raw: Raw): Result<Config, AllErrors<FieldError>> = resultAll {
 *     val port by parsePort(raw.port).collect()
 *     val host by parseHost(raw.host).collect()
 *     Config(port, host)
 * }
 * ```
 *
 * `collect()` records a step's error and hands back its value for later; reading a failed one ends the block.
 * Collect every step before reading any, or the steps after an early read never run and their errors are
 * missing. `bind()` reads at once, ending the block at that step. A failed step fails the block even when
 * nothing reads it.
 */
public inline fun <S, E : ResultError> resultAll(block: CollectScope<E>.() -> S): Result<S, AllErrors<E>> {
    val scope = CollectScope<E>()
    return try {
        val value = scope.block()
        scope.close()?.let { Err(it) } ?: Ok(value)
    } catch (signal: CollectSignal) {
        // A signal for an outer block belongs to that outer block.
        if (signal.scope !== scope) throw signal
        Err(checkNotNull(scope.close()))
    } finally {
        // Closing twice is harmless, and an exception passing through still has to close the scope.
        val _ = scope.close()
    }
}

// Not an Exception, so a `catch (e: Exception)` in the block lets it pass.
@PublishedApi
internal class CollectSignal(
    val scope: CollectScope<*>,
) : Throwable("a collected value read outside its resultAll block")
