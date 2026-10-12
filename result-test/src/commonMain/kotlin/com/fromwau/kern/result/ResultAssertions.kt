package com.fromwau.kern.result

import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * The value of this success, or a test failure naming the error that came instead.
 *
 * ```kotlin
 * val config = manager.load().assertSuccess()
 * ```
 */
public fun <S> Result<S, IError>.assertSuccess(): S = when (this) {
    is Result.Success -> value
    is Result.Error -> fail("expected Success but got $this")
}

/**
 * This error as the case [F] you expected, or a test failure naming what came instead: a success, or an error
 * of another case.
 *
 * ```kotlin
 * val busy = manager.update { it }.assertError<ConfigWriteError.LockBusy>()
 * ```
 */
public inline fun <reified F : IError> Result<Any?, IError>.assertError(): F = when (this) {
    is Result.Success -> fail("expected Error ${F::class.simpleName} but got $this")
    is Result.Error -> error as? F ?: fail("expected ${F::class.simpleName} but got $error")
}

/**
 * Passes when this is a success equal to [expected], and fails naming what came instead: another value, or the
 * error.
 *
 * ```kotlin
 * parsePort("8080").assertSuccess(8080)
 * ```
 */
public fun <S> Result<S, IError>.assertSuccess(expected: S) {
    when (this) {
        is Result.Success -> assertEquals(expected, value)
        is Result.Error -> fail("expected Success($expected) but got $this")
    }
}

/**
 * Passes when this is an error equal to [expected], and fails naming what came instead: another error, or the
 * success.
 *
 * ```kotlin
 * findUser(8).assertError(CrudError.NotFound(8))
 * ```
 */
public fun Result<Any?, IError>.assertError(expected: IError) {
    when (this) {
        is Result.Success -> fail("expected Error($expected) but got $this")
        is Result.Error -> assertEquals(expected, error)
    }
}
