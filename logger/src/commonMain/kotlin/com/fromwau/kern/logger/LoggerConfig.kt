package com.fromwau.kern.logger

/**
 * What a [Logger] sends its entries to, and at which settings, changed with [Logger.configure].
 *
 * ```kotlin
 * Log.configure { config ->
 *     config.copy(sinks = config.sinks.map { it.copy(config = it.config.copy(level = LogLevel.DEBUG)) })
 * }.onError { error -> Log.tag("App").w(error) { "keeping the old levels" } }
 * ```
 *
 * @param sinks every sink with its settings, in the order each entry reaches them. Names must be unique within the
 *   list.
 */
public data class LoggerConfig(
    val sinks: List<ConfiguredSink> = listOf(ConfiguredSink(PlatformSink())),
)

/**
 * One sink in a [LoggerConfig], with the settings it writes at. A sink object that stays in the list across a
 * [Logger.configure] keeps its queue and health, and takes the [config] of its new entry from the next entry logged.
 *
 * ```kotlin
 * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(StderrSink(), SinkConfig(level = LogLevel.DEBUG))) }
 *     .onError { error -> Log.tag("App").w(error) { "no debug output" } }
 * ```
 */
public data class ConfiguredSink(
    val sink: LogSink,
    val config: SinkConfig = SinkConfig(),
)
