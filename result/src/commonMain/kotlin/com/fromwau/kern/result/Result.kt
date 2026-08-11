package com.fromwau.kern.result

/**
 * A typed-error result: an expected outcome is [Success], an expected failure is [Error].
 *
 * Return one of these instead of throwing, so a caller has to handle the failure to reach the value, and
 * the failure keeps its own type instead of collapsing into a message string. [E] is bounded by [IError],
 * so an error is always a type you declared.
 *
 * ```kotlin
 * fun findUser(id: Long): Result<User, CrudError> =
 *     users[id]?.let { Ok(it) } ?: Err(CrudError.NotFound(id))
 * ```
 */
public sealed interface Result<out S, out E : IError> {
    /** The value the operation produced. */
    public data class Success<out S>(val value: S) : Result<S, Nothing>

    /** The failure the operation produced, typed as your own error rather than a message string. */
    public data class Error<out E : IError>(val error: E) : Result<Nothing, E>
}

/** Shorthand for [Result.Success]: `Ok("done")`. */
public typealias Ok<S> = Result.Success<S>

/** Shorthand for [Result.Error]: `Err(CrudError.NotFound(id))`. */
public typealias Err<E> = Result.Error<E>

/** A result whose success carries no value, built as `Ok(Unit)`: the work either completed or failed. */
public typealias EmptyResult<E> = Result<Unit, E>

/** Applies [transform] to a success value, passing an error through untouched. */
public inline fun <S, E : IError, T> Result<S, E>.map(transform: (S) -> T): Result<T, E> = when (this) {
    is Result.Success -> Result.Success(transform(value))
    is Result.Error -> this
}

/** Applies [transform] to an error, passing a success value through untouched. */
public inline fun <S, E : IError, F : IError> Result<S, E>.mapError(transform: (E) -> F): Result<S, F> =
    when (this) {
        is Result.Success -> this
        is Result.Error -> Result.Error(transform(error))
    }

/** Returns the success value, or what [fallback] makes of the error. */
public inline fun <S, E : IError> Result<S, E>.getOrElse(fallback: (E) -> S): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fallback(error)
}

/** Returns the success value, or null on a failure. */
public fun <S, E : IError> Result<S, E>.getOrNull(): S? = when (this) {
    is Result.Success -> value
    is Result.Error -> null
}

/** Returns the error, or null on a success. */
public fun <S, E : IError> Result<S, E>.errorOrNull(): E? = when (this) {
    is Result.Success -> null
    is Result.Error -> error
}

/** Collapses both cases into one type: [onSuccess] for a value, [onError] for a failure. */
public inline fun <S, E : IError, T> Result<S, E>.fold(onSuccess: (S) -> T, onError: (E) -> T): T =
    when (this) {
        is Result.Success -> onSuccess(value)
        is Result.Error -> onError(error)
    }

/** Recovers from a failure with another attempt, which may fail with an error of its own type. */
public inline fun <S, E : IError, F : IError> Result<S, E>.orElse(
    attempt: (E) -> Result<S, F>,
): Result<S, F> = when (this) {
    is Result.Success -> this
    is Result.Error -> attempt(error)
}

/** Runs [action] on a success value and returns this result unchanged, for chaining. */
public inline fun <S, E : IError> Result<S, E>.onSuccess(action: (S) -> Unit): Result<S, E> =
    also { if (it is Result.Success) action(it.value) }

/** Runs [action] on an error and returns this result unchanged, for chaining. */
public inline fun <S, E : IError> Result<S, E>.onError(action: (E) -> Unit): Result<S, E> =
    also { if (it is Result.Error) action(it.error) }
