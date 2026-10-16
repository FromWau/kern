package com.fromwau.kern.logger

import kotlin.time.Instant

/**
 * What one sink has failed to take, read from [InstalledSink.health]. Counts are since the sink was configured. It
 * changes only when something is dropped, fails or recovers, so collecting it costs nothing while all is well.
 *
 * @param dropped entries the sink never got: a full queue, a [SinkFailure.Drop] failure or its backoff, what a close
 *   gave up on, or lines a `FileSink` could not put back after its file was deleted.
 * @param failures writes that failed, retries included, and closes that failed.
 * @param failingSince the first failure of the current streak, or null once a write succeeds again.
 * @param lastError the most recent failure, kept after the sink recovers; null if it never failed.
 */
public data class SinkHealth(
    val dropped: Long = 0,
    val failures: Long = 0,
    val failingSince: Instant? = null,
    val lastError: SinkError? = null,
)
