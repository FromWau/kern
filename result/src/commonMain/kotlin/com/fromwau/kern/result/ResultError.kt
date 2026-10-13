package com.fromwau.kern.result

/**
 * The root of every error a [Result] can carry. Seal your own hierarchy under it, one case per failure, and a
 * `when` over it stays exhaustive: a failure added later is a compile error wherever a caller has to react.
 *
 * ```kotlin
 * sealed interface CrudError : ResultError {
 *     data class NotFound(val id: Long) : CrudError
 *     data class Invalid(val reason: String) : CrudError
 * }
 * ```
 */
public interface ResultError {
    /**
     * Names this kind of error for grouping and search, such as the issue a crash reporter files it under. It is the
     * same for every instance of one case, so keep values out of it.
     *
     * By default it is the class's simple name, `NotFound` for `CrudError.NotFound(3)`, and `ResultError` for an
     * anonymous object. Two hierarchies with a case of the same name share it; namespace every case of a sealed
     * hierarchy in one line:
     *
     * ```kotlin
     * sealed interface PaymentError : ResultError {
     *     override val errorType: String get() = "Payment.${this::class.simpleName}"
     *
     *     data object Declined : PaymentError
     * }
     * ```
     *
     * A class's name changes when you rename it, and code shrinking such as R8 replaces it with a short one like `a`.
     * Where the type must survive both, give each case a literal:
     *
     * ```kotlin
     * data object Declined : PaymentError {
     *     override val errorType: String get() = "Payment.Declined"
     * }
     * ```
     */
    public val errorType: String get() = this::class.simpleName ?: "ResultError"
}
