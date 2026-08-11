package com.fromwau.kern.result

/**
 * The root of every error a [Result] can carry, so a function reports an expected failure by returning a
 * typed error rather than by throwing.
 *
 * Seal your own hierarchy under it, and a `when` over that hierarchy stays exhaustive: adding a failure
 * later becomes a compile error at every site that has to react, instead of a silent fall-through.
 *
 * ```kotlin
 * sealed interface CrudError : IError {
 *     data class NotFound(val id: Long) : CrudError
 *     data class Invalid(val reason: String) : CrudError
 *     data class DbError(val cause: Throwable) : CrudError
 * }
 * ```
 */
public interface IError