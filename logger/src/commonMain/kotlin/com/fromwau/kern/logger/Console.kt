package com.fromwau.kern.logger

/**
 * Where [LoggerConfig.console] writes each line. [STDERR] keeps a CLI's standard output for its data, so
 * `app | jq` reads only what the app printed. Android's logcat is a single stream and takes both alike.
 */
public enum class Console {
    OFF,
    STDOUT,
    STDERR,
}
