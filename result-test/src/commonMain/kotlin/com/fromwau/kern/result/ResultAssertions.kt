package com.fromwau.kern.result

import kotlin.test.fail

/**
 * The value of this success, or a test failure naming the error that came instead.
 *
 * ```kotlin
 * val config = manager.load().assertSuccess()
 * ```
 */
@IgnorableReturnValue
public fun <S> Result<S, ResultError>.assertSuccess(): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fail("expected Success but got $this")
}

/**
 * This error as the case [F] you expected, or a test failure naming what came instead: a success, or an error
 * of another case. [F] is not checked against the result's error type, so a case it can never produce compiles
 * and fails when run.
 *
 * ```kotlin
 * val busy = manager.update { it }.assertError<ConfigWriteError.LockBusy>()
 * ```
 */
@IgnorableReturnValue
public inline fun <reified F : ResultError> Result<Any?, ResultError>.assertError(): F = when (this) {
    is Result.Success -> fail("expected Error ${F::class.simpleName} but got $this")
    is Result.Error -> error as? F ?: fail("expected ${F::class.simpleName} but got $error")
}

/**
 * This error when [check] holds for it, or a test failure naming what came instead: a success, or an error the
 * check refused. Inside [check] the error has the result's own type, so an `is` against a case the result can
 * never hold does not compile, and a case's fields can be checked in the same call:
 *
 * ```kotlin
 * findUser(8).assertError { it is CrudError.NotFound && it.id == 8L }
 * ```
 */
@IgnorableReturnValue
public fun <E : ResultError> Result<Any?, E>.assertError(check: (E) -> Boolean): E = when (this) {
    is Result.Success -> fail("expected an error matching the check but got $this")
    is Result.Error -> error.takeIf(check) ?: fail("expected an error matching the check but got $error")
}
