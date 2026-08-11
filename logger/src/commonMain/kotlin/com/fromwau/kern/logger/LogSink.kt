package com.fromwau.kern.logger

/**
 * An extra destination for entries that passed the level threshold.
 *
 * The console and the file are built in and driven by [LoggerRuntimeState]. A sink is for everything
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
     * rather than blocking here. A throw is caught and reported to the console, and never surfaces at the
     * call site that logged.
     *
     * @param entry what was logged, already filtered but not yet rendered.
     * @param state the runtime state in force for this entry.
     */
    public fun write(entry: LogEntry, state: LoggerRuntimeState)
}
