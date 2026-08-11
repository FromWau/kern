package com.fromwau.kern.result

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

private sealed interface CrudError : IError {
    data class NotFound(val id: Long) : CrudError
    data class Invalid(val reason: String) : CrudError
    data class DbError(val cause: Throwable) : CrudError
}

/** What the data layer's [CrudError] is mapped to once it crosses the boundary. */
private sealed interface ApiError : IError {
    data class Missing(val message: String) : ApiError
    data object Unavailable : ApiError
}

class ResultTest {
    private data class User(val id: Long, val name: String)

    // a fake room query call
    @Suppress("RedundantSuspendModifier")
    private suspend fun internalFindUserById(id: Long): User? {
        return when (id) {
            99L -> throw RuntimeException("internal db error") // fake a db error
            1L -> User(1, "John")
            2L -> User(2, "Jane")
            3L -> User(3, "Jack")
            else -> null
        }
    }

    // this is how you would wrap a room query in a Result type, returning an error
    // if the user is not found or if the id is invalid
    private suspend fun findUserById(id: Long): Result<User, CrudError> {
        return try {
            if (id <= 0) return Err(CrudError.Invalid("id must be positive"))

            val user = internalFindUserById(id)
            if (user == null) {
                Err(CrudError.NotFound(id))
            } else {
                Ok(user)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return Err(CrudError.DbError(e))
        }
    }

    private suspend fun deleteUserById(id: Long): EmptyResult<CrudError> = findUserById(id).map { }

    // a second fallible step, which is the shape flatMap exists to chain onto the first
    private fun renameTo(user: User, name: String): Result<User, CrudError> =
        if (name.isBlank()) Err(CrudError.Invalid("name must not be blank")) else Ok(user.copy(name = name))

    @Test
    fun `Ok and Err construct a success and a failure`() = runTest {
        assertEquals(Ok(User(1, "John")), findUserById(1))
        assertEquals(Err(CrudError.NotFound(4)), findUserById(4))
        assertEquals(Err(CrudError.Invalid("id must be positive")), findUserById(0))
    }

    @Test
    fun `EmptyResult succeeds without carrying a value`() = runTest {
        assertEquals(Ok(Unit), deleteUserById(1))
        assertEquals(Err(CrudError.NotFound(4)), deleteUserById(4))
        assertNull(deleteUserById(4).getOrNull())
    }

    @Test
    fun `a thrown query failure is caught as a typed error`() = runTest {
        val failed = assertIs<CrudError.DbError>(findUserById(99).errorOrNull())
        assertEquals("internal db error", failed.cause.message)
        assertNull(findUserById(1).errorOrNull())
    }

    @Test
    fun `map getOrElse getOrNull and fold read both cases`() = runTest {
        assertEquals(Ok("John"), findUserById(1).map { it.name })
        assertEquals("John", findUserById(1).map { it.name }.getOrElse { "anonymous" })
        assertEquals("anonymous", findUserById(4).map { it.name }.getOrElse { "anonymous" })

        assertEquals(User(2, "Jane"), findUserById(2).getOrNull())
        assertNull(findUserById(4).getOrNull())
        assertEquals(CrudError.NotFound(4), findUserById(4).errorOrNull())

        assertEquals("Jane", findUserById(2).fold(onSuccess = { it.name }, onError = { "none" }))
        assertEquals("none", findUserById(4).fold(onSuccess = { it.name }, onError = { "none" }))
    }

    @Test
    fun `flatMap chains a second step that can itself fail`() = runTest {
        assertEquals(Ok(User(1, "Jane")), findUserById(1).flatMap { renameTo(it, "Jane") })

        // The second step's failure is the result, even though the first step succeeded.
        assertEquals(
            Err(CrudError.Invalid("name must not be blank")),
            findUserById(1).flatMap { renameTo(it, "") },
        )
    }

    @Test
    fun `flatMap does not run its transform on an error`() = runTest {
        var ran = false

        val result = findUserById(4).flatMap {
            ran = true
            renameTo(it, "unreachable")
        }

        assertEquals(Err(CrudError.NotFound(4)), result)
        assertFalse(ran, "the transform must not run once the chain has already failed")
    }

    @Test
    fun `mapError and orElse change the error type`() = runTest {
        val mapped: Result<User, ApiError> = findUserById(4).mapError { crud ->
            when (crud) {
                is CrudError.NotFound -> ApiError.Missing("no user ${crud.id}")
                is CrudError.Invalid -> ApiError.Missing(crud.reason)
                is CrudError.DbError -> ApiError.Unavailable
            }
        }
        assertEquals(Err(ApiError.Missing("no user 4")), mapped)

        val recovered: Result<User, ApiError> = findUserById(4).orElse { Ok(User(0, "guest")) }
        assertEquals(Ok(User(0, "guest")), recovered)

        val untouched: Result<User, ApiError> = findUserById(1).orElse { Ok(User(0, "guest")) }
        assertEquals(Ok(User(1, "John")), untouched)
    }

    @Test
    fun `onSuccess and onError pass the result through unchanged`() = runTest {
        var failed: CrudError? = null
        val failure = findUserById(4)
            .onSuccess { error("unreachable") }
            .onError { failed = it }
        assertEquals(CrudError.NotFound(4), failed)
        assertEquals(Err(CrudError.NotFound(4)), failure)

        var found: String? = null
        val success = findUserById(1)
            .onSuccess { found = it.name }
            .onError { error("unreachable") }
        assertEquals("John", found)
        assertEquals(Ok(User(1, "John")), success)
    }
}
