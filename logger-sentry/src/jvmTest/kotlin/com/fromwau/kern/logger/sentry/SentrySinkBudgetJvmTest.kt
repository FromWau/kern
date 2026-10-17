package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SentrySinkBudgetJvmTest {
    @Test
    fun `an event over budget fills it to within a few bytes however its text is escaped`() =
        assertEachBulkFillsBudget("event")

    @Test
    fun `a breadcrumb over budget fills it to within a few bytes however its text is escaped`() =
        assertEachBulkFillsBudget("breadcrumb")

    @Test
    fun `a log over budget fills it to within a few bytes however its text is escaped`() =
        assertEachBulkFillsBudget("log")

    /** One entry per text of [bulks], each far over every budget, whose [destination] object must fill its budget. */
    private fun assertEachBulkFillsBudget(destination: String) = withSentry {
        bulks.forEach { bulk ->
            SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("bulk" to bulk.repeat(20_000))))
        }

        val budget = budgets.getValue(destination)
        sentObjects(destination).zip(bulks).forEach { (sent, bulk) ->
            val size = serializedSize(sent)
            assertTrue(size in budget - CUT_SLACK..budget, "$destination of ${bulk.codePointAt(0)} takes $size")
        }
    }

    @Test
    fun `a field of 1 MiB as text is cut to each destination's budget`() = withSentry {
        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("bytes" to ByteArray(1 shl 20))))

        val sizes = sentSizes()
        sentValues().forEach { (destination, values) ->
            val value = values["bytes"] as String
            assertTrue(value.endsWith(TRUNCATED), "$destination ends in ${value.takeLast(20)}")
            val budget = budgets.getValue(destination)
            assertTrue(sizes.getValue(destination) in budget - CUT_SLACK..budget, "$destination takes $sizes")
        }
    }

    @Test
    fun `an entry's text over its budget is cut largest first on all three`() = withSentry {
        val long = (0..<3).associate { "long$it" to "x".repeat(9_000) }
        val short = mapOf("id" to "order-42", "region" to "eu-west")

        SentrySink().writeAll(
            entry(LogLevel.ERROR, "measured", fields = long + short, error = LongText("e".repeat(9_000))),
        )

        val keys = long.keys + short.keys + "error"
        val sizes = sentSizes()
        sentValues().forEach { (destination, values) ->
            val texts = values.filterKeys { it in keys }.mapValues { it.value as String }
            assertEquals(keys, texts.keys, destination)
            val budget = budgets.getValue(destination)
            assertTrue(sizes.getValue(destination) in budget - CUT_SLACK..budget, "$destination takes $sizes")
            assertEquals(short, texts.filterKeys { it in short }, destination)
            (long.keys + "error").forEach { key ->
                assertTrue(texts.getValue(key).endsWith(TRUNCATED), "$destination $key")
            }
            val cut = long.keys.map { jsonCost(texts.getValue(it)) }.toSet()
            assertEquals(1, cut.size, "$destination cuts the long fields to $cut")
        }
    }

    @Test
    fun `a cut never splits an emoji`() = withSentry {
        // Three ASCII bytes leave room for half an emoji at the cut.
        val text = "abc" + "😀".repeat(2_100)

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("emoji" to text)))

        sentValues().forEach { (destination, values) ->
            val value = values.getValue("emoji") as String
            assertEquals(value, value.encodeToByteArray().decodeToString(), destination)
            assertTrue(value.endsWith(TRUNCATED), destination)
            assertTrue(value.encodeToByteArray().size <= budgets.getValue(destination), destination)
        }
    }

    @Test
    fun `fields are left out rather than cut below 256 bytes on all three`() = withSentry {
        val fields = mapOf("id" to "order-42") + (0..<150).associate { "long$it" to "x".repeat(9_000) }

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        sentValues().forEach { (destination, values) ->
            val kept = values.filterKeys { it in fields }.mapValues { it.value as String }
            assertEquals(fields.keys.take(kept.size).toSet(), kept.keys, destination)
            assertEquals("order-42", kept["id"], destination)
            (kept - "id").values.forEach { value ->
                assertTrue(value.endsWith(TRUNCATED) && jsonCost(value) >= 256, "$destination keeps ${jsonCost(value)}")
            }
            assertEquals("${fields.size - kept.size} fields left out", values[LEFT_OUT], destination)
        }
    }

    @Test
    fun `thousands of short fields fill each destination at its own cost, none of them cut`() = withSentry {
        val fields = (0..<5_000).associate { "f${it.toString().padStart(4, '0')}" to "v".repeat(20) }

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        val keptBy = sentValues().mapValues { (destination, values) ->
            val kept = values.filterKeys { it in fields }
            assertEquals(fields.keys.take(kept.size).toSet(), kept.keys, destination)
            assertEquals(fields.filterKeys { it in kept }, kept, destination)
            assertEquals("${fields.size - kept.size} fields left out", values[LEFT_OUT], destination)
            kept.size
        }
        val sizes = sentSizes()
        budgets.forEach { (destination, budget) ->
            // Whole fields only, so up to one field short.
            val field = fieldCost(destination, fields.keys.first(), fields.values.first())
            assertTrue(sizes.getValue(destination) in budget - field..budget, "$destination takes $sizes")
        }
        assertTrue(keptBy.getValue("event") > keptBy.getValue("log"), "$keptBy")
    }

    @Test
    fun `an entry of many short fields keeps the first that fit and counts the rest on all three`() = withSentry {
        // Empty values, so the left-out field's own JSON decides whether one more field fits.
        val fields = (0..<3_000).associate { it.toString().padStart(4, '0') to "" }

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields, error = DiskFull(3)))

        sentValues().forEach { (destination, values) ->
            val kept = values.filterKeys { it in fields }.mapValues { it.value as String }
            assertEquals(fields.keys.take(kept.size).toSet(), kept.keys, destination)
            val leftOut = values[LEFT_OUT] as String
            assertEquals("${fields.size - kept.size} fields left out", leftOut, destination)
            assertEquals("DiskFull(free=3)", values["error"], destination)
            val cost = sentSizes().getValue(destination)
            val budget = budgets.getValue(destination)
            assertTrue(cost <= budget, "$destination costs $cost")
            val next = fields.keys.elementAt(kept.size)
            // One more field, and the count of those left out one digit longer at most.
            assertTrue(cost + fieldCost(destination, next, "") > budget + 1, "$destination has room for $next")
        }
    }

    @Test
    fun `a long error and tag beside hundreds of fields keep at least 1 KiB of the error on all three`() =
        withSentry {
            val fields = (0..<600).associate { "f${it.toString().padStart(3, '0')}" to "v".repeat(20) }

            SentrySink().writeAll(
                entry(
                    LogLevel.ERROR,
                    "measured",
                    tag = "t".repeat(100),
                    fields = fields,
                    error = LongText("e".repeat(20_000)),
                ),
            )

            val sizes = sentSizes()
            sentValues().forEach { (destination, values) ->
                val error = values["error"] as String
                assertTrue(
                    error.endsWith(TRUNCATED) && jsonCost(error) >= 1_024,
                    "$destination keeps ${jsonCost(error)}",
                )
                val budget = budgets.getValue(destination)
                assertTrue(sizes.getValue(destination) in budget - CUT_SLACK..budget, "$destination takes $sizes")
            }
        }

    @Test
    fun `an entry that fills a log's 8 KiB exactly reaches the log and the event untouched`() = withSentry {
        val (fields, error) = fillingBudget("log")

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields, error = error))

        val expected = fields + ("error" to error.toString())
        val sent = sentValues()
        assertEquals(budgets.getValue("log"), sentSizes().getValue("log"))
        assertEquals(expected, sent.getValue("log").filterKeys { it in expected })
        assertEquals(expected, sent.getValue("event").filterKeys { it in expected })
        assertTrue((sent.getValue("breadcrumb")["a"] as String).endsWith(TRUNCATED))
    }

    @Test
    fun `an entry that fills an event's 8 KiB exactly reaches the event untouched and is cut on the log`() =
        withSentry {
            val (fields, error) = fillingBudget("event")

            SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields, error = error))

            val expected = fields + ("error" to error.toString())
            val sent = sentValues()
            assertEquals(budgets.getValue("event"), sentSizes().getValue("event"))
            assertEquals(expected, sent.getValue("event").filterKeys { it in expected })
            assertTrue((sent.getValue("log")["b"] as String).endsWith(TRUNCATED))
        }

    @Test
    fun `an entry that fills a breadcrumb's 2 KiB exactly reaches it untouched`() = withSentry {
        val (fields, error) = fillingBudget("breadcrumb")

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields, error = error))

        val expected = fields + ("error" to error.toString())
        assertEquals(budgets.getValue("breadcrumb"), sentSizes().getValue("breadcrumb"))
        assertEquals(expected, sentValues().getValue("breadcrumb").filterKeys { it in expected })
    }

    /**
     * Two fields and an error whose [destination] object takes exactly its budget as sentry-java serializes it,
     * measured from the same entry with the field `b` empty, then cleared from what was recorded.
     */
    private fun SentRecords.fillingBudget(destination: String): Pair<Map<String, String>, LongText> {
        val error = LongText("e".repeat(500))
        val a = "a".repeat(budgets.getValue(destination) / 4)
        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("a" to a, "b" to ""), error = error))
        val rest = budgets.getValue(destination) - sentSizes().getValue(destination)
        clear()
        return mapOf("a" to a, "b" to "b".repeat(rest)) to error
    }

    @Test
    fun `a field that fills 2 KiB beside unquoted numbers on a breadcrumb reaches it untouched`() = withSentry {
        val budget = budgets.getValue("breadcrumb")
        val numbers = mapOf("long" to Long.MAX_VALUE, "flag" to true)
        SentrySink(events = LogLevel.OFF, logs = LogLevel.OFF)
            .writeAll(entry(LogLevel.ERROR, "measured", fields = mapOf("exact" to "") + numbers))
        val exact = "a".repeat(budget - serializedSize(breadcrumbs.single().data))
        breadcrumbs.clear()
        val fields = mapOf("exact" to exact) + numbers

        SentrySink(events = LogLevel.OFF, logs = LogLevel.OFF)
            .writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        assertEquals(fields, breadcrumbs.single().data.filterKeys { it in fields })
        assertEquals(budget, serializedSize(breadcrumbs.single().data))
    }

    @Test
    fun `numbers beside texts cut to fit leave each destination within its budget`() = withSentry {
        val fields = (0..<20).associate { "long$it" to "x".repeat(9_000) } +
            (0..<50).associate { "n$it" to Long.MAX_VALUE }

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        val sizes = sentSizes()
        budgets.forEach { (destination, budget) ->
            assertTrue(sizes.getValue(destination) <= budget, "$destination takes $sizes")
        }
    }

    @Test
    fun `a long text is left out before fields that cost less in full even where cutting it would cost less`() =
        withSentry {
            // On a breadcrumb each number takes 400 bytes and the text 3,007 in full but 263 cut to its floor.
            val numbers = (0..<5).associate { "${it}".padEnd(395, 'k') to 1 }
            val fields = mapOf("text" to "x".repeat(3_000)) + numbers

            SentrySink(events = LogLevel.OFF, logs = LogLevel.OFF)
                .writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

            val data = breadcrumbs.single().data
            assertEquals(numbers, data.filterKeys { it in fields })
            assertEquals("1 field left out", data[LEFT_OUT])
        }

    @Test
    fun `a field whose key alone is over 8 KiB is left out alone and counted on all three`() = withSentry {
        val fields = mapOf("id" to "order-42", "k".repeat(100_000) to "v", "after" to "short")

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        sentValues().forEach { (destination, values) ->
            assertEquals(
                mapOf("id" to "order-42", "after" to "short", LEFT_OUT to "1 field left out"),
                values.filterKeys { it in fields || it == LEFT_OUT },
                destination,
            )
        }
    }

    @Test
    fun `a field named like the count of fields left out gives way to it on all three`() = withSentry {
        val fields = mapOf(LEFT_OUT to "mine", "id" to "order-42", "k".repeat(100_000) to "v")

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", fields = fields))

        sentValues().forEach { (destination, values) ->
            assertEquals("1 field left out", values[LEFT_OUT], destination)
            assertEquals("order-42", values["id"], destination)
        }
    }

    @Test
    fun `a tag that alone takes more than a log's budget leaves the log's fields out`() = withSentry {
        val tag = "t".repeat(20_000)

        SentrySink().writeAll(entry(LogLevel.ERROR, "measured", tag = tag, fields = mapOf("count" to 1)))

        val log = logs.single().attributes.orEmpty()
        assertEquals(tag, log["tag"]?.value)
        assertEquals("1 field left out", log[LEFT_OUT]?.value)
        assertEquals("1", events.single().getExtra("count"))
    }

    @Test
    fun `a log cuts a throwable's long message to its budget and the event keeps the throwable whole`() =
        withSentry {
            val failure = IllegalStateException("m".repeat(300_000))

            SentrySink().writeAll(entry(LogLevel.ERROR, "saving failed", throwable = failure))

            val message = logs.single().attributes?.get("exception.message")?.value as String
            assertTrue(message.startsWith("mmm") && message.endsWith(TRUNCATED), message.takeLast(20))
            assertTrue(sentSizes().getValue("log") <= budgets.getValue("log"), "${sentSizes()}")
            assertEquals(300_000, events.single().throwable?.message?.length)
        }

    @Test
    fun `a throwable's long message shares the log's budget with the error and the fields`() = withSentry {
        val fields = (0..<3).associate { "long$it" to "x".repeat(9_000) }

        SentrySink().writeAll(
            entry(
                LogLevel.ERROR,
                "saving failed",
                fields = fields,
                throwable = IllegalStateException("m".repeat(300_000)),
                error = LongText("e".repeat(9_000)),
            ),
        )

        val log = logs.single().attributes.orEmpty().mapValues { it.value.value }
        listOf("exception.message", "error").forEach { key ->
            val text = log[key] as String
            assertTrue(text.endsWith(TRUNCATED) && jsonCost(text) >= 1_024, "$key keeps ${jsonCost(text)}")
        }
        val budget = budgets.getValue("log")
        assertTrue(sentSizes().getValue("log") in budget - CUT_SLACK..budget, "${sentSizes()}")
    }

    @Test
    fun `an event cuts the message beside a throwable to its budget`() = withSentry {
        val message = "m".repeat(300_000)

        SentrySink().writeAll(entry(LogLevel.ERROR, message, throwable = IllegalStateException("boom")))

        val extra = events.single().getExtra("message") as String
        assertTrue(extra.startsWith("mmm") && extra.endsWith(TRUNCATED), extra.takeLast(20))
        assertTrue(sentSizes().getValue("event") <= budgets.getValue("event"), "${sentSizes()}")
    }

    @Test
    fun `the log's user from the user field counts toward its budget`() = withSentry {
        val long = (0..<20).associate { "long$it" to "x".repeat(9_000) }

        SentrySink(user = "userId").writeAll(entry(LogLevel.ERROR, "measured", fields = long + ("userId" to "user-42")))

        val attributes = logs.single().attributes.orEmpty()
        assertEquals("user-42", attributes["user.id"]?.value)
        val size = serializedSize(attributes.filterKeys { it == "user.id" || it !in sentryLogAttributes })
        val budget = budgets.getValue("log")
        // Every long text is cut to one length, so the log may fall up to a byte short per text.
        assertTrue(size in budget - long.size..budget, "the log takes $size")
    }

    @Test
    fun `an event after a full trail of large entries and a deep chain of causes serializes under Sentry's 1 MB`() =
        withSentry {
            val sink = SentrySink()
            val large = mapOf("request" to "r".repeat(20_000), "response" to "s".repeat(20_000))

            repeat(100) { sink.writeAll(entry(LogLevel.INFO, "step $it", fields = large)) }
            val failure = RuntimeException("short", deep(20))
            sink.writeAll(entry(LogLevel.ERROR, "failed", fields = large, throwable = failure))

            val event = events.single()
            assertEquals(21, event.exceptions.orEmpty().size)
            assertEquals(100, event.breadcrumbs.orEmpty().size)
            val size = serializedSize(event)
            assertTrue(size < 1_000_000, "the event takes $size bytes")
        }
}

// Each takes more than one byte in sentry-java's JSON: escaped as 2 or 6, or 2 and 4 bytes of UTF-8.
private val bulks = listOf("\"", Char(1).toString(), "\n", "é", "😀")

/** [causes] throwables, each the cause of the one before it. */
private fun deep(causes: Int): Throwable =
    if (causes == 1) RuntimeException("cause 1") else RuntimeException("cause $causes", deep(causes - 1))
