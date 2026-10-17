package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.ConfiguredSink
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.LogLevel
import com.fromwau.kern.logger.LogSink
import com.fromwau.kern.logger.Logger
import com.fromwau.kern.logger.LoggerConfig
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.result.ResultError
import com.fromwau.kern.result.assertSuccess
import io.sentry.Breadcrumb
import io.sentry.JsonObjectWriter
import io.sentry.NoOpLogger
import io.sentry.SentryEvent
import io.sentry.SentryLogEvent
import io.sentry.SentryOptions
import io.sentry.kotlin.multiplatform.Sentry
import java.io.StringWriter
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.time.Instant

// Sentry is one global per JVM: this relies on JUnit running test classes and tests one at a time, its default, and on
// every test closing Sentry before the next starts.

/** What the sink sent through Sentry, recorded in place of sending it. */
internal class SentRecords {
    val events = CopyOnWriteArrayList<SentryEvent>()
    val breadcrumbs = CopyOnWriteArrayList<Breadcrumb>()
    val logs = CopyOnWriteArrayList<SentryLogEvent>()

    fun clear() {
        events.clear()
        breadcrumbs.clear()
        logs.clear()
    }
}

/**
 * Runs [block] with Sentry initialised to record each event, breadcrumb and log instead of sending it. The Java
 * options, rather than the multiplatform ones, since only a Java event carries its extras.
 */
internal fun withSentry(block: SentRecords.() -> Unit) {
    val sent = SentRecords()
    withSentryOptions(
        { options ->
            options.setBeforeSend { event, _ ->
                sent.events += event
                null
            }
            options.setBeforeBreadcrumb { breadcrumb, _ ->
                sent.breadcrumbs += breadcrumb
                breadcrumb
            }
            options.logs.setBeforeSend { log ->
                sent.logs += log
                null
            }
        },
    ) { sent.block() }
}

/** Runs [block] with Sentry initialised with logs on and [configure], closing it after. */
internal fun withSentryOptions(
    configure: (SentryOptions) -> Unit,
    block: () -> Unit,
) {
    resetSentryGlobals()
    Sentry.initWithPlatformOptions { options ->
        options.dsn = "https://key@127.0.0.1/1"
        options.logs.isEnabled = true
        configure(options)
    }
    try {
        block()
    } finally {
        Sentry.close()
        resetSentryGlobals()
    }
}

// Sentry.close() keeps the global scope's breadcrumbs and user, and the next init replays the breadcrumbs.
internal fun resetSentryGlobals() {
    io.sentry.Sentry.getGlobalScope().clearBreadcrumbs()
    io.sentry.Sentry.getGlobalScope().user = null
    forkRootScopes.set(::forkSentryRootScopes)
}

// Returns Unit, since JUnit skips a test function that returns a value.
internal fun SentrySink.writeAll(vararg entries: LogEntry) = entries.forEach { entry ->
    write(entry, SinkConfig(level = LogLevel.VERBOSE)).assertSuccess()
}

internal fun sentryLogger(sink: LogSink): Logger = Logger().also { logger ->
    logger.configure { LoggerConfig(listOf(ConfiguredSink(sink))) }.assertSuccess()
}

internal fun entry(
    level: LogLevel,
    message: String,
    tag: String = "app",
    fields: Map<String, Any?> = emptyMap(),
    throwable: Throwable? = null,
    error: ResultError? = null,
    timestamp: Instant = Instant.parse("2026-08-11T12:34:56.789Z"),
) = LogEntry(timestamp, tag, level, message, fields, throwable, error)

/** One entry at every level an entry can have, each with its level's name as the message. */
internal fun everyLevel(): Array<LogEntry> = LogLevel.entries
    .filter { it != LogLevel.OFF }
    .map { entry(it, it.name) }
    .toTypedArray()

internal data class DiskFull(val free: Int) : ResultError

internal data class LongText(val text: String) : ResultError

internal const val TRUNCATED = "…(truncated)"

internal const val LEFT_OUT = "…"

internal val budgets = mapOf("event" to 8_192, "breadcrumb" to 2_048, "log" to 8_192)

// Each cut lands a few bytes short of the budget, since a character or its escape cannot be split.
internal const val CUT_SLACK = 5

// The attributes sentry-java adds to every log, beside the sink's.
internal val sentryLogAttributes = setOf(
    "sentry.sdk.name",
    "sentry.sdk.version",
    "sentry.environment",
    "sentry.release",
    "sentry.trace.parent_span_id",
    "server.address",
    "user.id",
    "user.name",
    "user.email",
)

/** Each object the sink sent to [destination]: an event's extras, a breadcrumb's data or a log's own attributes. */
internal fun SentRecords.sentObjects(destination: String): List<Map<String, Any?>> = when (destination) {
    "event" -> events.map { it.extras.orEmpty() }
    "breadcrumb" -> breadcrumbs.map { it.data }
    "log" -> logs.map { log -> log.attributes.orEmpty().filterKeys { it !in sentryLogAttributes } }
    else -> error("no destination $destination")
}

/** What each destination's object of the single entry sent takes as sentry-java serializes it, by destination. */
internal fun SentRecords.sentSizes(): Map<String, Int> =
    budgets.keys.associateWith { serializedSize(sentObjects(it).single()) }

/** The values the single entry sent reached each destination with, by destination. */
internal fun SentRecords.sentValues(): Map<String, Map<String, Any?>> = mapOf(
    "event" to events.single().extras.orEmpty(),
    "breadcrumb" to breadcrumbs.single().data,
    "log" to logs.single().attributes.orEmpty().mapValues { it.value.value },
)

/** What [value] takes as sentry-java serializes it, in UTF-8. */
internal fun serializedSize(value: Any): Int {
    val json = StringWriter().also { writer ->
        JsonObjectWriter(writer, SentryOptions().maxDepth).value(NoOpLogger.getInstance(), value)
    }
    return json.toString().utf8Size()
}

/** What [text] takes inside a JSON string as sentry-java writes it, in UTF-8. */
internal fun jsonCost(text: String): Int {
    val json = StringWriter().also { JsonObjectWriter(it, 1).value(text) }.toString()
    return json.utf8Size() - 2
}

/**
 * What a text field takes on [destination] beyond its key and value: an event's extra or a breadcrumb's data
 * `"":"",`, a log attribute `"":{"type":"string","value":""},`.
 */
internal fun fieldCost(
    destination: String,
    key: String,
    value: String,
): Int = (if (destination == "log") 32 else 6) + jsonCost(key) + jsonCost(value)

private fun String.utf8Size(): Int = encodeToByteArray().size
