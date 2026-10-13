package com.fromwau.kern.result

import kotlinx.serialization.Serializable

/**
 * One success holding every value in order, or the first error. Use it when several independent results all
 * have to succeed.
 */
public fun <T, E : ResultError> Iterable<Result<T, E>>.combine(): Result<List<T>, E> = mapResult { it }

/**
 * Runs [transform] on each item in order and collects the values, stopping at the first error: no item after
 * it is transformed, and that error is the result.
 *
 * ```kotlin
 * val ports: Result<List<Int>, PortError> = listOf("80", "443").mapResult(::parsePort)
 * ```
 */
public inline fun <T, R, E : ResultError> Iterable<T>.mapResult(transform: (T) -> Result<R, E>): Result<List<R>, E> {
    val values = if (this is Collection<*>) ArrayList<R>(size) else ArrayList()
    for (item in this) {
        values += transform(item).getOrElse { return Err(it) }
    }
    return Ok(values)
}

/** Where a [mapResultPartial] stopped: the values of the items before the failing one, and its [error]. */
@Serializable(with = PartialSerializer::class)
public data class Partial<out S, out E : ResultError>(val done: List<S>, val error: E) : ResultError

/**
 * [mapResult] that keeps what went through: it stops at the first error as well, and the error is [Partial],
 * holding the values of the items before it with that error, as a batch save reports what it already wrote.
 *
 * ```kotlin
 * fun importAll(orders: List<Order>): Result<List<OrderId>, ImportError> =
 *     orders.mapResultPartial { save(it) }.mapError { ImportError.Stopped(it.done, it.error) }
 * ```
 */
public inline fun <T, R, E : ResultError> Iterable<T>.mapResultPartial(
    transform: (T) -> Result<R, E>,
): Result<List<R>, Partial<R, E>> {
    val values = if (this is Collection<*>) ArrayList<R>(size) else ArrayList()
    for (item in this) {
        values += transform(item).getOrElse { return Err(Partial(values, it)) }
    }
    return Ok(values)
}

/**
 * Combines this success value with [other]'s through [transform], or fails with the first error in argument
 * order. [transform] runs only when both succeeded.
 *
 * ```kotlin
 * val endpoint = parseHost(rawHost).zip(parsePort(rawPort)) { host, port -> Endpoint(host, port) }
 * ```
 */
public inline fun <A, B, E : ResultError, T> Result<A, E>.zip(
    other: Result<B, E>,
    transform: (A, B) -> T,
): Result<T, E> {
    val a = getOrElse { return Err(it) }
    val b = other.getOrElse { return Err(it) }
    return Ok(transform(a, b))
}

/** [zip] for three results: [transform] runs only when all three succeeded, otherwise the first error wins. */
public inline fun <A, B, C, E : ResultError, T> Result<A, E>.zip(
    second: Result<B, E>,
    third: Result<C, E>,
    transform: (A, B, C) -> T,
): Result<T, E> {
    val a = getOrElse { return Err(it) }
    val b = second.getOrElse { return Err(it) }
    val c = third.getOrElse { return Err(it) }
    return Ok(transform(a, b, c))
}

/**
 * Runs [transform] on every item and collects the values in order, or every error in order, where [mapResult]
 * stops at the first:
 *
 * ```kotlin
 * val entries: Result<List<Entry>, AllErrors<LineError>> = lines.mapResultAll(::parseLine)
 * ```
 */
public inline fun <T, R, E : ResultError> Iterable<T>.mapResultAll(
    transform: (T) -> Result<R, E>,
): Result<List<R>, AllErrors<E>> {
    val values = ArrayList<R>()
    val errors = ArrayList<E>()
    for (item in this) {
        when (val result = transform(item)) {
            is Result.Success -> values += result.value
            is Result.Error -> errors += result.error
        }
    }
    return if (errors.isEmpty()) Ok(values) else Err(AllErrors(errors))
}

/**
 * Every success value and every error, each in order: what succeeded is kept, where [mapResultAll] gives either
 * every value or only the errors.
 *
 * ```kotlin
 * val (orders, bad) = lines.map(::parseLine).partition()
 * ```
 */
public fun <S, E : ResultError> Iterable<Result<S, E>>.partition(): Pair<List<S>, List<E>> {
    val values = ArrayList<S>()
    val errors = ArrayList<E>()
    for (result in this) {
        when (result) {
            is Result.Success -> values += result.value
            is Result.Error -> errors += result.error
        }
    }
    return values to errors
}

/**
 * Combines two successes through [transform], or fails with every error in argument order, where [zip] stops at
 * the first. [transform] runs only when both succeeded.
 *
 * ```kotlin
 * val config = zipAll(checkPort(raw.port), checkHost(raw.host)) { port, host -> Config(port, host) }
 * ```
 */
public inline fun <A, B, E : ResultError, T> zipAll(
    first: Result<A, E>,
    second: Result<B, E>,
    transform: (A, B) -> T,
): Result<T, AllErrors<E>> {
    if (first is Result.Success && second is Result.Success) return Ok(transform(first.value, second.value))
    return Err(AllErrors(listOfNotNull(first.errorOrNull(), second.errorOrNull())))
}

/** [zipAll] for three results: [transform] runs only when all three succeeded, otherwise every error comes back. */
public inline fun <A, B, C, E : ResultError, T> zipAll(
    first: Result<A, E>,
    second: Result<B, E>,
    third: Result<C, E>,
    transform: (A, B, C) -> T,
): Result<T, AllErrors<E>> {
    if (first is Result.Success && second is Result.Success && third is Result.Success) {
        return Ok(transform(first.value, second.value, third.value))
    }
    return Err(AllErrors(listOfNotNull(first.errorOrNull(), second.errorOrNull(), third.errorOrNull())))
}
