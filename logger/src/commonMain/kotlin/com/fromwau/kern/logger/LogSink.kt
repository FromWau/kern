package com.fromwau.kern.logger

/**
 * An extra destination for entries that passed the level threshold.
 *
 * The console and the file are built in and driven by [LoggerConfig]. A sink is for everything
 * else: forwarding failures to a crash reporter, feeding an in-app log viewer, or reaching a platform
 * facility kern does not use such as Apple's `os_log`.
 *
 * ```kotlin
 * val logger = Logger(
 *     sinks = listOf(
 *         LogSink { entry, _ ->
 *             if (entry.level == LogLevel.ERROR) crashReporter.record(entry.message, entry.throwable)
 *         },
 *     ),
 * )
 * ```
 */
public fun interface LogSink {
    /**
     * Handles one entry, rendering it however this sink wants.
     *
     * Called on the thread that logged and holding the logger's lock, so hand work to your own queue
     * rather than blocking here. Whatever this throws reaches the call site that logged, so handle the failures
     * of your own work here: catch the network or database error and queue, count or drop the entry.
     *
     * @param entry what was logged, already filtered but not yet rendered.
     * @param config the configuration in force for this entry.
     */
    public fun write(entry: LogEntry, config: LoggerConfig)
}
