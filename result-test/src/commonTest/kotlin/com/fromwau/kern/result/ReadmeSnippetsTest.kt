package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertEquals

// The Testing snippets of result's README, kept here so they compile against the real assertions and do what the
// README says. What they call but do not declare comes last in the class.
class ReadmeSnippetsTest {

    @Test
    fun `the assertions give the value or the error and an exact result compares with kotlin-test`() {
        val user = findUser(7).assertSuccess()
        findUser(8).assertError { it is CrudError.NotFound && it.id == 8L }
        assertEquals(Err(CrudError.NotFound(8)), findUser(8))

        assertEquals(User(7), user)
    }

    @Test
    fun `assertError with a check returns the result's error type and with a case returns that case`() {
        val error: CrudError = findUser(8).assertError { it is CrudError.NotFound }    // E: no .id here
        val missing = findUser(8).assertError<CrudError.NotFound>()                    // F: missing.id is 8

        assertEquals(CrudError.NotFound(8), error)
        assertEquals(8L, missing.id)
    }

    // Not in the README: what the snippets call.

    data class User(val id: Long)

    sealed interface CrudError : ResultError {
        data class NotFound(val id: Long) : CrudError
    }

    private fun findUser(id: Long): Result<User, CrudError> =
        if (id == 7L) Ok(User(id)) else Err(CrudError.NotFound(id))
}
