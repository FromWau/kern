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
 *
 * Name the case, do not carry the sentence. A lone `data class Failed(val message: String) : IError` is a
 * string in a box: it satisfies the bound and buys nothing, since a caller can still only print it. Text on
 * one case is fine, as `Invalid` shows above. Text as the hierarchy's *contract* is not: an `IError`
 * subinterface declaring `val message: String` makes every implementer a message carrier, which picks the
 * user's words in the layer that raised the error.
 */
public interface IError
