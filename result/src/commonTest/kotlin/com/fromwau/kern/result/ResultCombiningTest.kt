package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private sealed interface ParseError : IError {
    data class NotANumber(val given: String) : ParseError
    data class Negative(val given: Int) : ParseError
}

private fun parse(raw: String): Result<Int, ParseError> {
    val number = raw.toIntOrNull() ?: return Err(ParseError.NotANumber(raw))
    return if (number < 0) Err(ParseError.Negative(number)) else Ok(number)
}

class ResultCombiningTest {

    @Test
    fun `combine gathers every success value in order`() {
        assertEquals(Ok(listOf(1, 2, 3)), listOf(parse("1"), parse("2"), parse("3")).combine())
    }

    @Test
    fun `combine fails with the first error`() {
        val results = listOf(parse("1"), parse("x"), parse("-2"))

        assertEquals(Err(ParseError.NotANumber("x")), results.combine())
    }

    @Test
    fun `combine of nothing is an empty success`() {
        assertEquals(Ok(emptyList()), emptyList<Result<Int, ParseError>>().combine())
    }

    @Test
    fun `mapResult maps every item when each step succeeds`() {
        assertEquals(Ok(listOf(1, 2, 3)), listOf("1", "2", "3").mapResult(::parse))
    }

    @Test
    fun `mapResult stops at the first error without running the rest`() {
        val seen = mutableListOf<String>()

        val result = listOf("1", "x", "3").mapResult { raw ->
            seen += raw
            parse(raw)
        }

        assertEquals(Err(ParseError.NotANumber("x")), result)
        assertEquals(listOf("1", "x"), seen)
    }

    @Test
    fun `zip combines two successes`() {
        assertEquals(Ok(3), parse("1").zip(parse("2")) { a, b -> a + b })
    }

    @Test
    fun `zip combines three successes`() {
        assertEquals(Ok(6), parse("1").zip(parse("2"), parse("3")) { a, b, c -> a + b + c })
    }

    @Test
    fun `zip fails with the first error in argument order and does not run its transform`() {
        var ran = false

        val bothFailed = parse("x").zip(parse("-2")) { a, b ->
            ran = true
            a + b
        }
        val lastFailed = parse("1").zip(parse("2"), parse("-3")) { a, b, c ->
            ran = true
            a + b + c
        }

        assertEquals(Err(ParseError.NotANumber("x")), bothFailed)
        assertEquals(Err(ParseError.Negative(-3)), lastFailed)
        assertFalse(ran, "the transform must not run once any input has failed")
    }

    @Test
    fun `toResult turns a value into a success and null into the error asked for`() {
        val present: String? = "ada"
        val absent: String? = null

        assertEquals(Ok("ada"), present.toResult { ParseError.NotANumber("") })
        assertEquals(Err(ParseError.NotANumber("")), absent.toResult { ParseError.NotANumber("") })
    }

    @Test
    fun `toResult does not build the error for a value`() {
        var built = false

        "ada".toResult {
            built = true
            ParseError.NotANumber("")
        }

        assertFalse(built, "the error must only be built for null")
    }
}
