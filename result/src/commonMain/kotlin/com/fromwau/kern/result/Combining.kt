package com.fromwau.kern.result

/**
 * One success holding every value in order, or the first error. Use it when several independent results all
 * have to succeed.
 */
public fun <T, E : IError> Iterable<Result<T, E>>.combine(): Result<List<T>, E> = mapResult { it }

/**
 * Runs [transform] on each item in order and collects the values, stopping at the first error: no item after
 * it is transformed, and that error is the result.
 *
 * ```kotlin
 * val ports: Result<List<Int>, PortError> = listOf("80", "443").mapResult(::parsePort)
 * ```
 */
public inline fun <T, R, E : IError> Iterable<T>.mapResult(transform: (T) -> Result<R, E>): Result<List<R>, E> {
    val values = if (this is Collection<*>) ArrayList<R>(size) else ArrayList()
    for (item in this) {
        values += transform(item).getOrElse { return Err(it) }
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
public inline fun <A, B, E : IError, T> Result<A, E>.zip(
    other: Result<B, E>,
    transform: (A, B) -> T,
): Result<T, E> {
    val a = getOrElse { return Err(it) }
    val b = other.getOrElse { return Err(it) }
    return Ok(transform(a, b))
}

/** [zip] for three results: [transform] runs only when all three succeeded, otherwise the first error wins. */
public inline fun <A, B, C, E : IError, T> Result<A, E>.zip(
    second: Result<B, E>,
    third: Result<C, E>,
    transform: (A, B, C) -> T,
): Result<T, E> {
    val a = getOrElse { return Err(it) }
    val b = second.getOrElse { return Err(it) }
    val c = third.getOrElse { return Err(it) }
    return Ok(transform(a, b, c))
}
