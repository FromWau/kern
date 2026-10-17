package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.DropPolicy
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.ResultError
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.log.SentryLogLevel
import kotlin.time.Duration

/** Why a [SentrySink] did not send an entry. */
public sealed interface SentryError : ResultError {
    /** Sentry.init has not run, Sentry was closed, or this target has no Sentry SDK (JS, Wasm, Linux, Windows). */
    public data object NotEnabled : SentryError
}

/**
 * Sends each entry to Sentry through the Sentry Kotlin Multiplatform SDK, which your app initialises and closes
 * itself. An entry goes to each of three destinations whose level it reaches:
 *
 * - [events]: an issue in Sentry. An entry with a throwable is captured as that exception, with the message as the
 *   extra `message`; one without is captured as its message.
 * - [breadcrumbs]: the trail of what happened, attached to the next event.
 * - [logs]: Sentry Logs, sent only when your app sets `options.logs.enabled = true`.
 *
 * ```kotlin
 * Sentry.init { options ->
 *     options.dsn = "https://<key>@<org>.ingest.sentry.io/<project>"
 *     options.logs.enabled = true
 * }
 * Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(SentrySink())) }
 *     .onError { error -> Log.tag("App").w(error) { "not logging to Sentry" } }
 * ```
 *
 * An entry takes at most 8 KiB on an event and on a log and 2 KiB on a breadcrumb; over that, the longest values are
 * cut first.
 *
 * Until `Sentry.init` has run, after `Sentry.close()`, and on a target without a Sentry SDK, a write that reaches a
 * destination fails with [SinkError.Dropped] carrying [SentryError.NotEnabled]. So add the sink once Sentry runs,
 * and remove it before Sentry closes, as when the user grants and revokes consent.
 *
 * @param name names the sink in errors and health; unique within a logger's config.
 * @param events the least severe entry sent as an event; `OFF` sends none.
 * @param breadcrumbs the least severe entry added as a breadcrumb; `OFF` sends none.
 * @param logs the least severe entry sent as a log; `OFF` sends none.
 * @param tags the fields an event also carries as tags, each value as its extra reads, cut to 200 characters ending in
 *   `…` when longer. A field whose value is null adds no tag, and one named `tag` or `error.type` gives way to the
 *   sink's own.
 * @param user the field whose value, as text and never cut, an event carries as its user's id and a log as its
 *   `user.id`. When the field is null or missing, both keep the user Sentry's scope gives them.
 * @param onFailure what happens to an entry Sentry was not enabled for: dropped by default, with no backoff, so the
 *   first entry after `Sentry.init` goes out.
 * @param capacity how many entries may wait for Sentry.
 * @param drop which waiting entry gives way when more than [capacity] wait.
 */
public class SentrySink(
    override val name: String = "sentry",
    public val events: LogLevel = LogLevel.ERROR,
    public val breadcrumbs: LogLevel = LogLevel.INFO,
    public val logs: LogLevel = LogLevel.INFO,
    public val tags: Set<String> = emptySet(),
    public val user: String? = null,
    override val onFailure: SinkFailure = SinkFailure.Drop(backoff = Duration.ZERO),
    override val capacity: Int = 10_000,
    override val drop: DropPolicy = DropPolicy.OLDEST,
) : LogSink {
    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> {
        val toEvent = entry.level >= events
        val toBreadcrumb = entry.level >= breadcrumbs
        val toLog = entry.level >= logs
        if (!toEvent && !toBreadcrumb && !toLog) return Ok(Unit)
        if (!Sentry.isEnabled()) return Err(SinkError.Dropped(SentryError.NotEnabled))

        val sent = SentryEntry(entry, tags, user)
        // The event first, so its breadcrumb trail does not end with the entry itself.
        if (toEvent) captureEvent(sent)
        if (toBreadcrumb) addBreadcrumb(sent)
        if (toLog) sendLog(sent)
        return Ok(Unit)
    }

    public companion object {
        /** Whether Sentry's SDK sends from this target: true on Android, the JVM and Apple, false elsewhere. */
        public val sendsHere: Boolean = sentrySendsHere

        /**
         * Clears the breadcrumbs every [SentrySink] added, so the next event carries no trail from before the call.
         * Call it when the user revokes consent, before `Sentry.close()`.
         *
         * On the JVM and Android this clears only Sentry's global scope, where the sink puts its breadcrumbs and
         * which outlives `Sentry.close()`; breadcrumbs your app adds with Sentry's API go to another scope and stay.
         * On Apple it clears the current scope, along with any breadcrumbs your app added there. Elsewhere Sentry
         * does nothing, and neither does this.
         */
        public fun clearBreadcrumbs() {
            clearSinkBreadcrumbs()
        }
    }
}

internal fun LogLevel.toSentryLevel(): SentryLevel = when (this) {
    LogLevel.VERBOSE, LogLevel.DEBUG -> SentryLevel.DEBUG
    LogLevel.INFO -> SentryLevel.INFO
    LogLevel.WARN -> SentryLevel.WARNING
    LogLevel.ERROR -> SentryLevel.ERROR
    LogLevel.WTF, LogLevel.OFF -> SentryLevel.FATAL
}

internal fun LogLevel.toSentryLogLevel(): SentryLogLevel = when (this) {
    LogLevel.VERBOSE -> SentryLogLevel.TRACE
    LogLevel.DEBUG -> SentryLogLevel.DEBUG
    LogLevel.INFO -> SentryLogLevel.INFO
    LogLevel.WARN -> SentryLogLevel.WARN
    LogLevel.ERROR -> SentryLogLevel.ERROR
    LogLevel.WTF, LogLevel.OFF -> SentryLogLevel.FATAL
}
