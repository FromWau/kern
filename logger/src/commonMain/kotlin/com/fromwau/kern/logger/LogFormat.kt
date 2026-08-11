package com.fromwau.kern.logger

/**
 * How an entry is rendered, for the console and the file alike.
 *
 * [TEXT] is a line for a human to read: local wall-clock time, level, tag, message. [JSON] is one object
 * per line, timestamped in UTC, for a log shipper to parse.
 */
public enum class LogFormat {
    TEXT,
    JSON,
}
