package com.fromwau.kern.logger

/**
 * How an entry is rendered, set apart for the console ([ConsoleOutput.Stdout] and its siblings) and the file
 * ([FileOutput.Append]), so a file can hold JSON while the console shows text.
 *
 * [TEXT] is a line for a human to read: local wall-clock time, level, tag, message. [JSON] is one object
 * per line, timestamped in UTC, for a log shipper to parse.
 */
public enum class LogFormat {
    TEXT,
    JSON,
}
