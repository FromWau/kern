package com.fromwau.kern.logger.test

import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Ok
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A sink for tests that keeps every entry it is handed. Add it to a [com.fromwau.kern.logger.Logger] and wait for
 * what your code logs:
 *
 * ```kotlin
 * val sink = TestSink()
 * val logger = Logger()
 * logger.configure { LoggerConfig(listOf(ConfiguredSink(sink, SinkConfig(LogLevel.VERBOSE)))) }.assertSuccess()
 *
 * Scanner(logger.tag("Scanner")).scan()
 *
 * assertEquals("scan complete", sink.awaitEntry().message)
 * ```
 *
 * Every write succeeds.
 *
 * @param name names the sink in its logger; unique within a logger's config.
 */
public class TestSink(override val name: String = "test") : LogSink {
    private val recorded = Received<LogEntry>()

    /**
     * Each entry, once, in the order the sink got them. An entry [awaitEntry] took is not in it, and one collected
     * here is not handed to [awaitEntry]. Collecting it never ends by itself.
     */
    public val entries: Flow<LogEntry> = recorded.flow

    /** Every entry the sink got so far, in order, whether or not [entries] or [awaitEntry] took it. */
    public val received: List<LogEntry>
        get() = recorded.snapshot

    /**
     * The next entry not yet taken from [entries] or by this call, waiting up to [timeout] of real time for it, even
     * under `runTest`'s virtual clock. Fails the test, naming the sink and the timeout, when none arrives in time.
     */
    public suspend fun awaitEntry(timeout: Duration = 1.seconds): LogEntry =
        recorded.await(timeout) { "TestSink \"$name\" got no entry within $timeout" }

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
        recorded.add(entry)
        return Ok(Unit)
    }
}
