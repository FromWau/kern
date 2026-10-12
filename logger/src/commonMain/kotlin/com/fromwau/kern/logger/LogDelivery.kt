package com.fromwau.kern.logger

/**
 * Where a [Logger] writes each entry to the file, the sinks and the console: on the thread that logged, with
 * [Inline], or on a writer of its own, such as `AsyncDelivery` from `kern:logger-async`.
 *
 * The level is checked and the message built on the thread that logged either way; only the writing moves.
 */
public interface LogDelivery {
    /** Runs [write], now or later. Writes submitted by one thread run in the order they were submitted. */
    public fun submit(write: () -> Unit)

    /** Returns once every write submitted before this call has run or been dropped. */
    public fun flush()

    public companion object {
        /**
         * Writes on the thread that logged, before the log call returns. A failure set to [FileFailure.Throw] or
         * [SinkFailure.Throw] therefore reaches that call.
         */
        public val Inline: LogDelivery = object : LogDelivery {
            override fun submit(write: () -> Unit) = write()

            override fun flush() = Unit
        }
    }
}
