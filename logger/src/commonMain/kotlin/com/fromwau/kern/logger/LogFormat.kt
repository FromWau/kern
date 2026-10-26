package com.fromwau.kern.logger

/**
 * How a sink renders an entry: [TEXT] is a line for a human to read, with local wall-clock time, level, tag and
 * message. [JSON] is one object per line, timestamped in UTC, for a log shipper to parse.
 */
public enum class LogFormat {
    /** `<time> <LEVEL> <tag> - <message> key=value ...`, with a stack trace on the lines below. */
    TEXT,

    /** One JSON object per line: `timestamp`, `tag`, `level`, `message`, `fields`, `stackTrace`, `error`. */
    JSON,
}

/** Whether a console line is wrapped in ANSI colour. A [LogFormat.JSON] line never is. */
public enum class ColorMode {
    /**
     * Colour only when the stream is a terminal. A non-empty `NO_COLOR` turns it off. Otherwise a `FORCE_COLOR` or
     * `CLICOLOR_FORCE` set to anything but empty, `0` or `false` turns it on, even into a pipe or under `TERM=dumb`.
     * Without those, `CLICOLOR=0` or `TERM=dumb` turns it off.
     */
    AUTO,

    /** Colour even when the stream is a pipe or a file, as CI logs usually want. */
    ALWAYS,

    /** Never colour. */
    NEVER,
}
