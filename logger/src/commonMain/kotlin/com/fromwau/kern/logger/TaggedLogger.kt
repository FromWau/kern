package com.fromwau.kern.logger

import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError

/**
 * A [Logger] that already knows where its entries come from, so a call site names only the level.
 *
 * ```kotlin
 * private val log = Log.tag("Scanner")
 *
 * log.i { "scan complete" }
 * log.i("count" to files.size, "cached" to true, "user" to session?.userId) { "scan complete" }
 * log.e(cause) { "scan failed" }
 * log.w(DbError.NotFound(id)) { "no such user" }
 * ```
 *
 * Each level has three forms: fields only, a [Throwable] whose stack trace is written under the message, and a
 * [ResultError] written as `error=...`. Fields are rendered as a `fields` object under [LogFormat.JSON] and as
 * `key=value` after the message under [LogFormat.TEXT]. A number or a `Boolean` becomes that JSON literal, null
 * becomes `null`, NaN and the infinities become strings, and anything else is written as its `toString()`, taken
 * when the sink writes the entry, so pass values that will not change. A key passed twice keeps its last value.
 *
 * Every `message` block is built only when a configured sink takes the level; if it throws, the entry is logged
 * with `<message threw ...>` in its place.
 */
public class TaggedLogger internal constructor(
    private val tag: String,
    private val logger: Logger,
) {
    /**
     * Logs at [LogLevel.VERBOSE].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun v(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.VERBOSE, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.VERBOSE], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun v(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.VERBOSE, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.VERBOSE], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun v(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.VERBOSE, error = error, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.DEBUG].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun d(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.DEBUG, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.DEBUG], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun d(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.DEBUG, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.DEBUG], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun d(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.DEBUG, error = error, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.INFO].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun i(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.INFO, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.INFO], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun i(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.INFO, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.INFO], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun i(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.INFO, error = error, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WARN].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun w(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WARN, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WARN], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun w(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WARN, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WARN], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun w(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WARN, error = error, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.ERROR].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun e(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.ERROR, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.ERROR], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun e(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.ERROR, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.ERROR], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun e(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.ERROR, error = error, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WTF].
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun wtf(vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WTF, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WTF], with [throwable]'s stack trace under the message.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun wtf(throwable: Throwable? = null, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WTF, throwable, fields = fields.asFields(), message = message)

    /**
     * Logs at [LogLevel.WTF], with [error] after the fields.
     *
     * @param message built only when a configured sink takes the level. If it throws, `<message threw ...>` is
     *   logged in its place.
     */
    public fun wtf(error: ResultError, vararg fields: Pair<String, Any?>, message: () -> String): Unit =
        logger.log(tag, LogLevel.WTF, error = error, fields = fields.asFields(), message = message)

    internal fun log(level: LogLevel, error: ResultError, message: () -> String) =
        logger.log(tag, level, error = error, message = message)
}

/**
 * Logs this result's error, when it is one, and returns this result unchanged:
 *
 * ```kotlin
 * val user = repository.find(id).logError(log) { "lookup of $id failed" }
 * ```
 *
 * @param message built only for an error, and only when a configured sink takes [level]. If it throws,
 *   `<message threw ...>` is logged in its place.
 */
@IgnorableReturnValue
public fun <S, E : ResultError> Result<S, E>.logError(
    log: TaggedLogger,
    level: LogLevel = LogLevel.ERROR,
    message: () -> String,
): Result<S, E> {
    if (this is Result.Error) log.log(level, error, message)
    return this
}

private fun Array<out Pair<String, Any?>>.asFields(): Map<String, Any?> = if (isEmpty()) emptyMap() else toMap()
