package com.fromwau.kern.logger

/**
 * How severe one entry is, and the threshold a sink's [SinkConfig.level] measures it against.
 *
 * An entry reaches a sink when its level is at or above the sink's, so a sink at `INFO` takes [INFO], [WARN],
 * [ERROR] and [WTF] and skips [DEBUG] and [VERBOSE]. Declaration order is the ranking.
 */
public enum class LogLevel(internal val severity: Int) {
    /** Detail for tracing a run step by step. */
    VERBOSE(0),

    /** Detail for a developer looking into a problem. */
    DEBUG(1),

    /** What the program is doing, at the level a user or operator follows. */
    INFO(2),

    /** Something unexpected that the program recovered from. */
    WARN(3),

    /** A failure the program could not recover from in this operation. */
    ERROR(4),

    /** A failure that should never happen. Logcat shows it as ASSERT, the unified log as FAULT. */
    WTF(5),

    /** As a sink's level, takes nothing. An entry logged at `OFF` is discarded. */
    OFF(6),
}
