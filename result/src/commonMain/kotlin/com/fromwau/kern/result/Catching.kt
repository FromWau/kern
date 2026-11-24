package com.fromwau.kern.result

import kotlin.coroutines.cancellation.CancellationException
import kotlin.reflect.KClass
import kotlin.reflect.safeCast

/**
 * Runs [block] and gives its value as a success, or an exception of the type [caught] names as an error. It adapts
 * third-party code that reports a failure by throwing, such as a database driver, to code that returns results:
 *
 * ```kotlin
 * fun loadUser(id: Long): Result<User, CrudError> =
 *     catching(SQLException::class, { CrudError.DbError(it) }) { dao.load(id) }
 * ```
 *
 * Name the type the code you call documents for its failure, as narrow as it is. A throw of any other type is not
 * caught: it propagates with its own stack trace.
 *
 * [block] cannot suspend; in suspending code use `suspendCatching` from `result-coroutines`. Unlike that, which can
 * make a timeout in a coroutine still running an error, this rethrows every [CancellationException], even when
 * [caught] is a supertype of it.
 *
 * @param caught the type of exception made an error, along with its subtypes.
 * @param toError makes your error from the exception [block] threw. A throw from this block is not caught.
 * @param block the work that may throw.
 */
public fun <X : Exception, S, E : ResultError> catching(
    caught: KClass<X>,
    toError: (X) -> E,
    block: () -> S,
): Result<S, E> = try {
    Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Err(toError(caught.safeCast(e) ?: throw e))
}
