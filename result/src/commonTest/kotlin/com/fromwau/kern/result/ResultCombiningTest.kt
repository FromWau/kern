package com.fromwau.kern.result

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

private sealed interface ParseError : ResultError {
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
    fun `partition splits values and errors each in order`() {
        val results = listOf(parse("1"), parse("x"), parse("2"), parse("-3"))

        assertEquals(
            listOf(1, 2) to listOf(ParseError.NotANumber("x"), ParseError.Negative(-3)),
            results.partition(),
        )
    }

    @Test
    fun `partition of all successes has no errors and of all errors no values`() {
        assertEquals(listOf(1, 2) to emptyList(), listOf(parse("1"), parse("2")).partition())
        assertEquals(emptyList<Int>() to listOf(ParseError.NotANumber("x")), listOf(parse("x")).partition())
    }

    @Test
    fun `partition keeps a success whose value is null`() {
        val results: List<Result<Int?, ParseError>> = listOf(Ok(null), Err(ParseError.NotANumber("x")), Ok(1))

        assertEquals(listOf(null, 1) to listOf(ParseError.NotANumber("x")), results.partition())
    }

    @Test
    fun `mapResultPartial maps every item when each step succeeds`() {
        assertEquals(Ok(listOf(1, 2, 3)), listOf("1", "2", "3").mapResultPartial(::parse))
    }

    @Test
    fun `mapResultPartial stops at the first error and keeps the values before it`() {
        val runs = mutableListOf<String>()

        val parsed = listOf("1", "2", "x", "4").mapResultPartial {
            runs += it
            parse(it)
        }

        assertEquals(Err(Partial(listOf(1, 2), ParseError.NotANumber("x"))), parsed)
        assertEquals(listOf("1", "2", "x"), runs)
    }

    @Test
    fun `mapResultPartial failing on the first item has nothing done`() {
        assertEquals(Err(Partial(emptyList(), ParseError.NotANumber("x"))), listOf("x", "1").mapResultPartial(::parse))
    }

    @Test
    fun `mapResultPartial of no items succeeds with none`() {
        assertEquals(Ok(emptyList()), emptyList<String>().mapResultPartial(::parse))
    }

    @Test
    fun `mapResultAll maps every item when each step succeeds`() {
        assertEquals(Ok(listOf(1, 2, 3)), listOf("1", "2", "3").mapResultAll(::parse))
    }

    @Test
    fun `mapResultAll runs every step and keeps every error in order`() {
        val runs = mutableListOf<String>()

        val parsed = listOf("x", "2", "-3").mapResultAll {
            runs += it
            parse(it)
        }

        assertEquals(Err(AllErrors(listOf(ParseError.NotANumber("x"), ParseError.Negative(-3)))), parsed)
        assertEquals(listOf("x", "2", "-3"), runs)
    }

    @Test
    fun `zipAll combines two or three successes`() {
        assertEquals(Ok(3), zipAll(parse("1"), parse("2")) { a, b -> a + b })
        assertEquals(Ok(6), zipAll(parse("1"), parse("2"), parse("3")) { a, b, c -> a + b + c })
    }

    @Test
    fun `zipAll keeps every error in argument order and does not run its transform`() {
        var ran = false

        val sum = zipAll(parse("x"), parse("2"), parse("-3")) { a, b, c ->
            ran = true
            a + b + c
        }

        assertEquals(Err(AllErrors(listOf(ParseError.NotANumber("x"), ParseError.Negative(-3)))), sum)
        assertFalse(ran)
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

        val _ = "ada".toResult {
            built = true
            ParseError.NotANumber("")
        }

        assertFalse(built, "the error must only be built for null")
    }
}
