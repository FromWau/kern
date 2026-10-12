package com.fromwau.kern.logger

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TestTimeSource

class RetryingSinkTest {
    private val time = TestTimeSource()
    private val config = LoggerConfig()

    private class Endpoint : LogSink {
        var up = false
        val received = mutableListOf<String>()

        override fun write(entry: LogEntry, config: LoggerConfig) {
            if (!up) throw IllegalStateException("endpoint is down")
            received += entry.message
        }
    }

    private fun entry(message: String) = LogEntry(Instant.fromEpochSeconds(0), "app", LogLevel.INFO, message)

    @Test
    fun `a refused entry is held and delivered in order once the backoff has passed`() {
        val endpoint = Endpoint()
        val sink = RetryingSink(endpoint, capacity = 10, drop = DropPolicy.OLDEST, backoff = 5.seconds, time)

        sink.write(entry("a"), config)
        sink.write(entry("b"), config)
        endpoint.up = true
        sink.write(entry("c"), config)
        assertEquals(emptyList(), endpoint.received, "nothing is retried before the backoff")
        assertEquals(3, sink.health.value.buffered)

        time += 5.seconds
        sink.write(entry("d"), config)

        assertEquals(listOf("a", "b", "c", "d"), endpoint.received)
        assertEquals(BufferHealth(failures = 1, lastError = sink.health.value.lastError), sink.health.value)
    }

    @Test
    fun `entries past the capacity are dropped and announced to the sink before the rest`() {
        val endpoint = Endpoint()
        val sink = RetryingSink(endpoint, capacity = 2, drop = DropPolicy.OLDEST, backoff = 1.seconds, time)

        listOf("a", "b", "c", "d").forEach { sink.write(entry(it), config) }
        endpoint.up = true
        time += 1.seconds
        sink.write(entry("e"), config)

        assertContains(endpoint.received.first(), "dropped 2 entries")
        assertEquals(listOf("c", "d", "e"), endpoint.received.drop(1))
        assertEquals(2, sink.health.value.dropped)
    }

    @Test
    fun `a wrapped sink limited to errors holds only errors while it is down`() {
        val endpoint = Endpoint()
        val limited = endpoint.atLeast(LogLevel.ERROR)
        val sink = RetryingSink(limited, capacity = 10, drop = DropPolicy.OLDEST, backoff = 1.seconds, timeSource = time)

        sink.write(entry("routine"), config)
        sink.write(entry("failure").copy(level = LogLevel.ERROR), config)

        assertEquals(1, sink.health.value.buffered)
        assertEquals(LogLevel.ERROR, sink.minLevel)
    }
}
