package com.fromwau.kern.logger

/**
 * A [Logger] that already knows where its entries come from, so a call site names only the level.
 *
 * ```kotlin
 * private val log = Log.tag("Scanner")
 *
 * log.i { "scan complete" }
 * log.i("count" to 412, "ms" to 1200) { "scan complete" }
 * log.e(cause) { "scan failed" }
 * ```
 *
 * Structured pairs are rendered as a `fields` object under [LogFormat.JSON] and as `key=value` after the
 * message under [LogFormat.TEXT]. Values are converted with `toString()`, so they need not be strings.
 */
public class TaggedLogger internal constructor(
    private val tag: String,
    private val logger: Logger,
) {
    /** Logs at [LogLevel.VERBOSE]. */
    public fun v(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.VERBOSE, fields = fields.asFields(), message = message)

    /** Logs at [LogLevel.DEBUG]. */
    public fun d(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.DEBUG, fields = fields.asFields(), message = message)

    /** Logs at [LogLevel.INFO]. */
    public fun i(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.INFO, fields = fields.asFields(), message = message)

    /** Logs at [LogLevel.WARN]. */
    public fun w(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WARN, fields = fields.asFields(), message = message)

    /** Logs at [LogLevel.WARN], with [throwable]'s stack trace under the message. */
    public fun w(throwable: Throwable, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WARN, throwable, fields.asFields(), message)

    /** Logs at [LogLevel.ERROR]. */
    public fun e(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.ERROR, fields = fields.asFields(), message = message)

    /** Logs at [LogLevel.ERROR], with [throwable]'s stack trace under the message. */
    public fun e(throwable: Throwable, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.ERROR, throwable, fields.asFields(), message)
}

private fun Array<out Pair<String, Any?>>.asFields(): Map<String, String> =
    if (isEmpty()) emptyMap() else associate { (key, value) -> key to value.toString() }
