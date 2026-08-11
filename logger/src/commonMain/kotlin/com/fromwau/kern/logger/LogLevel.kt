package com.fromwau.kern.logger

/**
 * How severe one entry is, and the threshold [LoggerRuntimeState.level] measures it against.
 *
 * An entry is written when its level is at or above the configured one, so `INFO` passes [INFO], [WARN]
 * and [ERROR] through and drops [DEBUG] and [VERBOSE]. Declaration order is the ranking.
 */
public enum class LogLevel(internal val severity: Int) {
    VERBOSE(0),
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
}
