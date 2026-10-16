package com.fromwau.kern.logger.test

import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.InstalledSink
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.result.assertSuccess

/** A logger with no sink. */
internal fun emptyLogger(): Logger = Logger().also { logger ->
    logger.configure { LoggerConfig(emptyList()) }.assertSuccess()
}

/** Adds [sink] with [config] at the end of the configured list and returns its handle. */
internal fun Logger.addOrFail(sink: LogSink, config: SinkConfig = SinkConfig(LogLevel.VERBOSE)): InstalledSink {
    configure { it.copy(sinks = it.sinks + ConfiguredSink(sink, config)) }.assertSuccess()
    return sinks.value.single { it.sink === sink }
}
