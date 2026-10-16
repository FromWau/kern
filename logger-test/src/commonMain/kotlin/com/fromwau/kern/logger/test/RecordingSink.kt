@file:OptIn(InternalKernApi::class)

package com.fromwau.kern.logger.test

import com.fromwau.kern.logger.BatchSink
import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.InternalKernApi
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.logger.Thrown
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import kotlinx.coroutines.flow.Flow
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Wraps a sink of your own to test it through a real [com.fromwau.kern.logger.Logger]: [delegate] does the work,
 * and every entry it is handed is recorded with the settings it got and what it returned.
 *
 * ```kotlin
 * val sink = RecordingSink(RemoteSink(server.url))
 * logger.configure { LoggerConfig(listOf(ConfiguredSink(sink))) }.assertSuccess()
 *
 * logger.tag("app").i { "hello" }
 *
 * assertEquals(Ok(Unit), sink.awaitCall().result)
 * ```
 *
 * The name, [onFailure], [capacity] and [drop] are the delegate's, and [close] closes it, so the logger treats the
 * wrapped sink as it treats the delegate. A batching delegate such as `FileSink` still gets queued entries several at
 * a time, and each entry of such a write is recorded as a call of its own with the write's result. A write that
 * throws is recorded as `Err(SinkError.Dropped(Thrown(e)))` for each of its entries, and the throw goes on to the
 * logger.
 */
public sealed class RecordingSink : LogSink {
    /** The sink that does the work. */
    public abstract val delegate: LogSink

    private val recorded = Received<SinkCall>()

    override val name: String
        get() = delegate.name

    override val onFailure: SinkFailure
        get() = delegate.onFailure

    override val capacity: Int
        get() = delegate.capacity

    override val drop: DropPolicy
        get() = delegate.drop

    /**
     * Each call, once, in the order they were made; a retried entry once per attempt. A call [awaitCall] took is not
     * in it, and one collected here is not handed to [awaitCall]. Collecting it never ends by itself.
     */
    public val calls: Flow<SinkCall> = recorded.flow

    /** Every call so far, in order, whether or not [calls] or [awaitCall] took it. */
    public val received: List<SinkCall>
        get() = recorded.snapshot

    /**
     * The next call not yet taken from [calls] or by this call, waiting up to [timeout] of real time for it, even
     * under `runTest`'s virtual clock. Fails the test, naming the sink and the timeout, when none comes in time.
     */
    public suspend fun awaitCall(timeout: Duration = 1.seconds): SinkCall =
        recorded.await(timeout) { "RecordingSink \"$name\" got no call within $timeout" }

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> =
        record(listOf(entry), config) { delegate.write(entry, config) }

    override fun close(): EmptyResult<SinkError> = delegate.close()

    /** Runs [call] and records each of [entries] with its result, or with the throw, which goes on. */
    internal fun record(
        entries: List<LogEntry>,
        config: SinkConfig,
        call: () -> EmptyResult<SinkError>,
    ): EmptyResult<SinkError> {
        val result = try {
            call()
        } catch (e: Throwable) {
            recordAll(entries, config, Err(SinkError.Dropped(Thrown(e))))
            throw e
        }
        recordAll(entries, config, result)
        return result
    }

    private fun recordAll(entries: List<LogEntry>, config: SinkConfig, result: EmptyResult<SinkError>) {
        entries.forEach { recorded.add(SinkCall(it, config, result)) }
    }
}

/**
 * Wraps [delegate] in a [RecordingSink] that the logger treats as it treats [delegate]: one write per entry, or
 * several entries per write for a batching sink such as `FileSink`.
 */
public fun RecordingSink(delegate: LogSink): RecordingSink =
    if (delegate is BatchSink) BatchRecordingSink(delegate) else EntryRecordingSink(delegate)

private class EntryRecordingSink(override val delegate: LogSink) : RecordingSink()

private class BatchRecordingSink(override val delegate: BatchSink) : RecordingSink(), BatchSink {
    override fun writeBatch(entries: List<LogEntry>, config: SinkConfig): EmptyResult<SinkError> =
        record(entries.toList(), config) { delegate.writeBatch(entries, config) }
}

/**
 * One entry a [RecordingSink]'s delegate was handed: the [entry] and [config] it was given and the [result] of the
 * write that carried it, or `Err(SinkError.Dropped(Thrown(e)))` when that write threw `e`.
 */
public data class SinkCall(
    val entry: LogEntry,
    val config: SinkConfig,
    val result: EmptyResult<SinkError>,
)
