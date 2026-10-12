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
 *         LogSink { entry, _ -> crashReporter.record(entry.message, entry.throwable) }.atLeast(LogLevel.ERROR),
 *     ),
 * )
 * ```
 */
public fun interface LogSink {
    /**
     * Handles one entry, rendering it however this sink wants.
     *
     * Called holding the logger's lock, on the thread that logged unless the logger's [LogDelivery] writes
     * elsewhere, so hand work to your own queue rather than blocking here. An exception thrown here is handled as
     * [LoggerConfig.sinkFailure] says, by default by dropping the entry for this sink alone; wrap the sink in
     * [RetryingSink] to hold and retry instead. An entry this sink logs while handling one never comes back to it.
     *
     * @param entry what was logged, already filtered but not yet rendered.
     * @param config the configuration in force for this entry.
     */
    public fun write(entry: LogEntry, config: LoggerConfig)

    /**
     * The least severe entry this sink takes. One below it is never offered, so it counts neither as a success
     * nor as a failure; see [atLeast].
     */
    public val minLevel: LogLevel
        get() = LogLevel.VERBOSE
}

/**
 * This sink, offered only entries at [level] or above.
 *
 * Filter this way rather than with an `if` inside [LogSink.write]: an entry the sink returns from at once counts as
 * a success, which resets [Breaker.Trip]'s count of failures in a row, so a crash reporter that is down would never
 * be skipped while routine entries keep arriving.
 */
public fun LogSink.atLeast(level: LogLevel): LogSink = LevelLimitedSink(this, level)

internal class LevelLimitedSink(val sink: LogSink, override val minLevel: LogLevel) : LogSink {
    override fun write(entry: LogEntry, config: LoggerConfig) {
        if (entry.level.severity >= minLevel.severity) sink.write(entry, config)
    }
}
