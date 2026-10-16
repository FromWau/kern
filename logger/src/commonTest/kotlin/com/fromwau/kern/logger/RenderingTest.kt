package com.fromwau.kern.logger

import com.fromwau.kern.result.ResultError
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Instant

class RenderingTest {

    private val entry = LogEntry(
        timestamp = Instant.parse("2026-08-11T12:34:56.789Z"),
        tag = "Scanner",
        level = LogLevel.INFO,
        message = "scan complete",
        fields = mapOf("count" to "412"),
    )

    @Test
    fun `a text line carries the level tag message and fields`() {
        val line = entry.toTextLine(TimeZone.UTC.asLocalClock())

        assertContains(line, "INFO")
        assertContains(line, "Scanner")
        assertContains(line, "scan complete")
        assertContains(line, "count=412")
    }

    @Test
    fun `a lone surrogate is escaped in json and replaced in text while a whole pair is kept`() {
        val damaged = entry.copy(message = "cut ${Char(0xD83D)} whole ${Char(0xD83D)}${Char(0xDE00)}")

        val json = damaged.toJsonLine()
        assertContains(json, "cut \\ud83d whole ${Char(0xD83D)}${Char(0xDE00)}")
        assertEquals(damaged.message, Json.parseToJsonElement(json).jsonObject["message"]?.jsonPrimitive?.content)
        val text = damaged.toTextLine(TimeZone.UTC.asLocalClock())
        assertContains(text, "cut ${Char(0xFFFD)} whole ${Char(0xD83D)}${Char(0xDE00)}")
    }

    @Test
    fun `a json timestamp always has nine fraction digits`() {
        val whole = entry.copy(timestamp = Instant.parse("2026-08-11T12:34:56Z"))

        assertContains(whole.toJsonLine(), "\"timestamp\":\"2026-08-11T12:34:56.000000000Z\"")
    }

    @Test
    fun `a json line nests the fields under their own key`() {
        val line = entry.toJsonLine()

        assertContains(line, "\"fields\":{\"count\":\"412\"}")
        assertContains(line, "\"level\":\"INFO\"")
        assertContains(line, "\"timestamp\":\"2026-08-11T12:34:56.789000000Z\"")
    }

    @Test
    fun `a json line stays one line even when the message spans several`() {
        val line = entry.copy(message = "he said \"hi\"\nthen left").toJsonLine()

        assertFalse(line.contains('\n'), "a shipper reads one object per line: $line")
        assertContains(line, "\\\"hi\\\"")
        assertContains(line, "\\n")
    }

    @Test
    fun `an entry without fields or a throwable omits both keys`() {
        val line = entry.copy(fields = emptyMap()).toJsonLine()

        assertFalse(line.contains("fields"))
        assertFalse(line.contains("stackTrace"))
    }

    @Test
    fun `a throwable is rendered under the message rather than folded into it`() {
        val withCause = entry.copy(throwable = IllegalStateException("boom"))

        assertContains(withCause.toTextLine(TimeZone.UTC.asLocalClock()), "scan complete")
        assertContains(withCause.toTextLine(TimeZone.UTC.asLocalClock()), "boom")
        assertContains(withCause.toJsonLine(), "\"stackTrace\"")
        assertEquals("scan complete", withCause.message)
    }

    @Test
    fun `a throwable whose message and toString throw is still rendered`() {
        val hostile = entry.copy(throwable = HostileException())

        val text = hostile.toTextLine(TimeZone.UTC.asLocalClock())
        val json = hostile.toJsonLine()

        assertContains(text, "scan complete")
        assertContains(json, "scan complete")
    }

    @Test
    fun `a json field carries its type in the literal`() {
        val typed = entry.copy(
            fields = mapOf(
                "port" to 5001,
                "big" to 9_007_199_254_740_993L,
                "ratio" to 0.5,
                "tls" to true,
                "user" to null,
                "host" to "0.0.0.0",
            ),
        )

        assertContains(
            typed.toJsonLine(),
            "\"fields\":{\"port\":5001,\"big\":9007199254740993,\"ratio\":0.5,\"tls\":true,\"user\":null," +
                "\"host\":\"0.0.0.0\"}",
        )
    }

    @Test
    fun `a json field JSON has no literal for is written as a string`() {
        val odd = entry.copy(
            fields = mapOf("nan" to Double.NaN, "inf" to Float.POSITIVE_INFINITY, "list" to listOf(1, 2)),
        )

        assertContains(odd.toJsonLine(), "\"fields\":{\"nan\":\"NaN\",\"inf\":\"Infinity\",\"list\":\"[1, 2]\"}")
    }

    @Test
    fun `arrays are written as their contents at any depth in text and json`() {
        val arrays = entry.copy(
            fields = mapOf(
                "ints" to intArrayOf(1, 2),
                "nested" to arrayOf<Any>(intArrayOf(1), arrayOf("x")),
                "inList" to listOf(intArrayOf(1)),
                "inMap" to mapOf("k" to byteArrayOf(7)),
            ),
        )

        assertContains(
            arrays.toTextLine(TimeZone.UTC.asLocalClock()),
            "scan complete ints=[1, 2] nested=[[1], [x]] inList=[[1]] inMap={k=[7]}",
        )
        assertContains(
            arrays.toJsonLine(),
            "\"fields\":{\"ints\":\"[1, 2]\",\"nested\":\"[[1], [x]]\",\"inList\":\"[[1]]\",\"inMap\":\"{k=[7]}\"}",
        )
    }

    @Test
    fun `a container that holds itself is written without overflowing the stack`() {
        val cycles = entry.copy(
            fields = mapOf(
                "list" to mutableListOf<Any>().apply { add(this) },
                "map" to mutableMapOf<String, Any>().apply { put("self", this) },
                "array" to arrayOfNulls<Any>(1).apply { set(0, listOf(this)) },
            ),
        )

        assertContains(
            cycles.toTextLine(TimeZone.UTC.asLocalClock()),
            "scan complete list=[(this Collection)] map={self=(this Map)} array=[[[...]]]",
        )
    }

    @Test
    fun `a value whose toString or iterator throws is written as a placeholder`() {
        val hostile = entry.copy(
            fields = mapOf(
                "value" to ToStringThrows,
                "iterator" to IteratorThrows(IllegalStateException()),
                "modified" to IteratorThrows(ConcurrentModificationException()),
            ),
        )

        val expected = "scan complete value=<toString() threw IllegalStateException> " +
            "iterator=<toString() threw IllegalStateException> " +
            "modified=<toString() threw ConcurrentModificationException>"
        assertContains(hostile.toTextLine(TimeZone.UTC.asLocalClock()), expected)
        assertContains(
            hostile.toJsonLine(),
            "\"modified\":\"<toString() threw ConcurrentModificationException>\"",
        )
    }

    @Test
    fun `a json field keeps Short and Byte as numbers and writes any other Number as text`() {
        val numbers = entry.copy(
            fields = mapOf("short" to 3.toShort(), "byte" to 4.toByte(), "custom" to Ratio(1, 3)),
        )

        assertContains(numbers.toJsonLine(), "\"fields\":{\"short\":3,\"byte\":4,\"custom\":\"1/3\"}")
    }

    @Test
    fun `a text field is written as key=value with null spelled out`() {
        val typed = entry.copy(fields = mapOf("port" to 5001, "tls" to true, "user" to null))

        assertContains(typed.toTextLine(TimeZone.UTC.asLocalClock()), "scan complete port=5001 tls=true user=null")
    }

    @Test
    fun `a text line ends the fields with the error`() {
        val failed = entry.copy(error = Missing(7))

        assertContains(failed.toTextLine(TimeZone.UTC.asLocalClock()), "scan complete count=412 error=Missing(id=7)")
    }

    @Test
    fun `a json line carries the error as a string`() {
        val failed = entry.copy(error = Missing(7))

        assertContains(failed.toJsonLine(), "\"error\":\"Missing(id=7)\"")
    }

    @Test
    fun `a json line without an error has no error key`() {
        assertFalse(entry.toJsonLine().contains("error"))
    }

    @Test
    fun `a text line without its timestamp starts at the level`() {
        val line = entry.render(LogFormat.TEXT, TimeZone.UTC.asLocalClock(), Timestamp.Off)

        assertEquals("INFO    Scanner - scan complete count=412", line)
    }

    @Test
    fun `a json line keeps its timestamp when the text one is off`() {
        val line = entry.render(LogFormat.JSON, TimeZone.UTC.asLocalClock(), Timestamp.Off)

        assertContains(line, "\"timestamp\":\"2026-08-11T12:34:56.789000000Z\"")
    }

    @Test
    fun `WTF is labelled WTF`() {
        val line = entry.copy(level = LogLevel.WTF).toTextLine(TimeZone.UTC.asLocalClock())

        assertContains(line, " WTF     Scanner - ")
        assertContains(entry.copy(level = LogLevel.WTF).toJsonLine(), "\"level\":\"WTF\"")
    }
}

private data class Missing(val id: Int) : ResultError

private object ToStringThrows {
    override fun toString(): String = throw IllegalStateException("toString")
}

/** A collection whose iteration fails with [failure], as one changed while it is read does. */
private class IteratorThrows(private val failure: Throwable) : AbstractList<Int>() {
    override val size: Int = 1

    override fun get(index: Int): Int = 1

    override fun iterator(): Iterator<Int> = throw failure
}

/** A [Number] of its own, written as its `toString()`. */
private class Ratio(private val over: Int, private val under: Int) : Number() {
    override fun toDouble(): Double = over.toDouble() / under

    override fun toFloat(): Float = toDouble().toFloat()

    override fun toLong(): Long = toDouble().toLong()

    override fun toInt(): Int = toDouble().toInt()

    override fun toShort(): Short = toInt().toShort()

    override fun toByte(): Byte = toInt().toByte()

    override fun toString(): String = "$over/$under"
}

/** An exception whose every description throws, as a broken `toString()` override does. */
internal class HostileException : Exception() {
    override val message: String
        get() = throw IllegalStateException("message")

    override fun toString(): String = throw IllegalStateException("toString")
}
