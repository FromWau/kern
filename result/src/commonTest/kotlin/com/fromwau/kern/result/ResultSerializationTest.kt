package com.fromwau.kern.result

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

@Serializable
private data class Account(val id: Long, val name: String)

/** A case carrying data and a case carrying none: the payload is what has to survive the trip. */
@Serializable
private sealed interface TransferError : ResultError {
    @Serializable
    @SerialName("not_found")
    data class NotFound(val id: Long) : TransferError

    @Serializable
    @SerialName("declined")
    data object Declined : TransferError
}

@Serializable
private data class Response(val account: Result<Account, TransferError>)

class ResultSerializationTest {

    private val serializer = ResultSerializer(Account.serializer(), TransferError.serializer())

    @Test
    fun `a success round trips with its value intact`() {
        val original: Result<Account, TransferError> = Ok(Account(7, "ada"))

        val decoded = Json.decodeFromString(serializer, Json.encodeToString(serializer, original))

        assertEquals(original, decoded)
    }

    @Test
    fun `an error round trips as its own case rather than as a message`() {
        val original: Result<Account, TransferError> = Err(TransferError.NotFound(7))

        val decoded = Json.decodeFromString(serializer, Json.encodeToString(serializer, original))

        // The whole point of the type: the id is still readable, not flattened into prose.
        val error = assertIs<Result.Error<TransferError>>(decoded).error
        assertEquals(7L, assertIs<TransferError.NotFound>(error).id)
    }

    @Test
    fun `each case encodes under its own key and carries nothing of the other`() {
        // Wire contract: moving or renaming the kern package must not change what services exchange.
        assertEquals(
            """{"success":{"id":7,"name":"ada"}}""",
            Json.encodeToString(serializer, Ok(Account(7, "ada"))),
        )
        assertEquals(
            """{"error":{"type":"declined"}}""",
            Json.encodeToString(serializer, Err(TransferError.Declined)),
        )
    }

    @Test
    fun `an EmptyResult round trips even though its success carries no value`() {
        val emptySerializer = ResultSerializer(Unit.serializer(), TransferError.serializer())
        val original: EmptyResult<TransferError> = Ok(Unit)

        val decoded = Json.decodeFromString(emptySerializer, Json.encodeToString(emptySerializer, original))

        assertEquals(original, decoded)
    }

    @Test
    fun `a result nests in another serializable type with nothing annotated at the use site`() {
        val original = Response(Err(TransferError.NotFound(7)))

        assertEquals(original, Json.decodeFromString<Response>(Json.encodeToString(original)))
    }

    @Test
    fun `every error a resultAll block collected round trips in order`() {
        val failed: Result<Account, AllErrors<TransferError>> =
            Err(AllErrors(listOf(TransferError.NotFound(7), TransferError.Declined)))

        val back = Json.decodeFromString<Result<Account, AllErrors<TransferError>>>(Json.encodeToString(failed))

        assertEquals(failed, back)
    }

    @Test
    fun `a stopped batch round trips with what was done and the error`() {
        val stopped: Result<List<Account>, Partial<Account, TransferError>> =
            Err(Partial(listOf(Account(1, "ada")), TransferError.Declined))

        val back = Json.decodeFromString<Result<List<Account>, Partial<Account, TransferError>>>(Json.encodeToString(stopped))

        assertEquals(stopped, back)
    }

    @Test
    fun `an AllErrors with no errors is refused when decoded`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Json.decodeFromString<AllErrors<TransferError>>("""{"errors":[]}""")
        }

        assertTrue("at least one error" in failure.message.orEmpty(), failure.message)
    }

    @Test
    fun `an object holding neither key is refused rather than decoded into a wrong case`() {
        assertFailsWith<SerializationException> { Json.decodeFromString(serializer, "{}") }
    }

    @Test
    fun `an object holding both keys is refused rather than decoded into one of them`() {
        val both = """{"success":{"id":7,"name":"ada"},"error":{"type":"declined"}}"""

        val failure = assertFailsWith<SerializationException> { Json.decodeFromString(serializer, both) }

        assertTrue("more than one" in failure.message.orEmpty(), failure.message)
    }
}
