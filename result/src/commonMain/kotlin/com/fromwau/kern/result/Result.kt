@file:OptIn(ExperimentalContracts::class)

package com.fromwau.kern.result

import kotlinx.serialization.Serializable
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

/**
 * A typed-error result: an expected outcome is [Success], an expected failure is [Error].
 *
 * Return one instead of throwing: a caller has to handle the failure to reach the value, and the failure keeps
 * its own type instead of collapsing into a message string.
 *
 * ```kotlin
 * fun findUser(id: Long): Result<User, CrudError> = users[id].toResult { CrudError.NotFound(id) }
 * ```
 *
 * A result is `@Serializable` when its value and error types are; kotlinx-serialization is an optional
 * dependency you add yourself.
 */
@Serializable(with = ResultSerializer::class)
public sealed interface Result<out S, out E : ResultError> {
    /** The value the operation produced. */
    public data class Success<out S>(val value: S) : Result<S, Nothing>

    /** The failure the operation produced, typed as your own error rather than a message string. */
    public data class Error<out E : ResultError>(val error: E) : Result<Nothing, E>
}

/** Shorthand for [Result.Success]: `Ok("done")`. */
public typealias Ok<S> = Result.Success<S>

/** Shorthand for [Result.Error]: `Err(CrudError.NotFound(id))`. */
public typealias Err<E> = Result.Error<E>

/** A result whose success carries no value, built as `Ok(Unit)`: the work either completed or failed. */
public typealias EmptyResult<E> = Result<Unit, E>

/**
 * True for a success, and the compiler then treats this result as a [Result.Success], so `value` reads directly:
 *
 * ```kotlin
 * if (result.isSuccess()) render(result.value)
 * ```
 */
public fun <S, E : ResultError> Result<S, E>.isSuccess(): Boolean {
    contract {
        returns(true) implies (this@isSuccess is Result.Success<S>)
        returns(false) implies (this@isSuccess is Result.Error<E>)
    }
    return this is Result.Success
}

/** True for an error, and the compiler then treats this result as a [Result.Error], so `error` reads directly. */
public fun <S, E : ResultError> Result<S, E>.isError(): Boolean {
    contract {
        returns(true) implies (this@isError is Result.Error<E>)
        returns(false) implies (this@isError is Result.Success<S>)
    }
    return this is Result.Error
}

/**
 * A success holding this value, or the error [error] builds when it is null.
 *
 * ```kotlin
 * fun findUser(id: Long): Result<User, CrudError> = users[id].toResult { CrudError.NotFound(id) }
 * ```
 */
public inline fun <T : Any, E : ResultError> T?.toResult(error: () -> E): Result<T, E> {
    contract { callsInPlace(error, InvocationKind.AT_MOST_ONCE) }
    return if (this != null) Ok(this) else Err(error())
}

/** Applies [transform] to a success value, passing an error through untouched. */
public inline fun <S, E : ResultError, T> Result<S, E>.map(transform: (S) -> T): Result<T, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Error -> this
}

/**
 * Runs [transform] on a success value when the next step can itself fail, passing an error through
 * untouched. Use this where [map] would hand you a `Result` nested inside a `Result`.
 *
 * ```kotlin
 * fun rename(id: Long, name: String): Result<User, CrudError> =
 *     findUser(id).flatMap { user -> save(user.copy(name = name)) }
 * ```
 */
public inline fun <S, E : ResultError, T> Result<S, E>.flatMap(
    transform: (S) -> Result<T, E>,
): Result<T, E> = when (this) {
    is Result.Success -> transform(value)
    is Result.Error -> this
}

/** Applies [transform] to an error, passing a success value through untouched. */
public inline fun <S, E : ResultError, F : ResultError> Result<S, E>.mapError(transform: (E) -> F): Result<S, F> =
    when (this) {
        is Result.Success -> this
        is Result.Error -> Result.Error(transform(error))
    }

/** Returns the success value, or what [fallback] makes of the error. */
public inline fun <S, E : ResultError> Result<S, E>.getOrElse(fallback: (E) -> S): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fallback(error)
}

/** Returns the success value, or null on a failure. */
public fun <S, E : ResultError> Result<S, E>.getOrNull(): S? = when (this) {
    is Result.Success -> value
    is Result.Error -> null
}

/** Returns the error, or null on a success. */
public fun <S, E : ResultError> Result<S, E>.errorOrNull(): E? = when (this) {
    is Result.Success -> null
    is Result.Error -> error
}

/** Collapses both cases into one type: [onSuccess] for a value, [onError] for a failure. */
public inline fun <S, E : ResultError, T> Result<S, E>.fold(onSuccess: (S) -> T, onError: (E) -> T): T =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Error -> onError(error)
    }

/** Recovers from a failure with another attempt, which may fail with an error of its own type. */
public inline fun <S, E : ResultError, F : ResultError> Result<S, E>.orElse(
    attempt: (E) -> Result<S, F>,
): Result<S, F> = when (this) {
    is Result.Success -> this
    is Result.Error -> attempt(error)
}

/** Runs [action] on a success value and returns this result unchanged, for chaining. */
@IgnorableReturnValue
public inline fun <S, E : ResultError> Result<S, E>.onSuccess(action: (S) -> Unit): Result<S, E> =
    also { if (it is Result.Success) action(it.value) }

/** Runs [action] on an error and returns this result unchanged, for chaining. */
@IgnorableReturnValue
public inline fun <S, E : ResultError> Result<S, E>.onError(action: (E) -> Unit): Result<S, E> =
    also { if (it is Result.Error) action(it.error) }
