package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult

/**
 * A [LogSink] that takes several queued entries in one call. Its worker hands it whatever is waiting, up to 256
 * entries, and a single entry as soon as it arrives; a sink that is not a [BatchSink] gets one [write] per entry.
 */
@InternalKernApi
public interface BatchSink : LogSink {
    /** Writes [entries] in order. An error fails them all: the sink's [onFailure] retries or drops them together. */
    public fun writeBatch(entries: List<LogEntry>, config: SinkConfig): EmptyResult<SinkError>
}
