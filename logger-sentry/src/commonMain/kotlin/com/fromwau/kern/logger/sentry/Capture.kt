@file:OptIn(InternalKernApi::class)

package com.fromwau.kern.logger.sentry

import com.fromwau.kern.logger.InternalKernApi
import com.fromwau.kern.logger.LogEntry
import com.fromwau.kern.logger.fieldText
import com.fromwau.kern.logger.typedFieldValue
import com.fromwau.kern.result.ResultError
import io.sentry.kotlin.multiplatform.SentryAttributes

/** Captures [sent] as an event: as its throwable when it has one, otherwise as its message. */
internal expect fun captureEvent(sent: SentryEntry)

/** Adds [sent] as a breadcrumb that the next event captured on any thread carries. */
internal expect fun addBreadcrumb(sent: SentryEntry)

/** Sends [sent] to Sentry Logs, which drops it unless the app enabled them. */
internal expect fun sendLog(sent: SentryEntry)

/** Removes the breadcrumbs [addBreadcrumb] added. */
internal expect fun clearSinkBreadcrumbs()

/**
 * One entry as every destination takes it, its fields fitted to the per-entry budget of each destination it reaches.
 *
 * @param promotedTags the fields that also become event tags.
 * @param userField the field that sets the user id of the event and the log.
 */
internal class SentryEntry(
    val entry: LogEntry,
    private val promotedTags: Set<String>,
    userField: String?,
) {
    val errorType: String? = entry.error?.errorTypeOrClassName()

    /** The user field's value as text, as logged: an id is never cut, so the event and the log name the same user. */
    val userId: String? = userField?.let { entry.fields[it] }?.let(::fieldText)

    /** The non-null fields, each a string, a boolean or a finite number. */
    private val values = entry.fields.mapNotNull { (key, value) -> value?.let { key to typedFieldValue(it) } }

    private val error = entry.error?.let { "error" to fieldText(it) }

    private val exceptionType = entry.throwable?.let { it::class.simpleName }

    private val forEvent by lazy {
        val message = entry.message.takeIf { entry.throwable != null }?.let { "message" to it }
        fitToEntryBudget(values, sinkTexts = listOfNotNull(error, message), sinkKeys = emptyList(), Destination.Event)
    }

    private val forBreadcrumb by lazy {
        val sinkKeys = listOfNotNull(errorType?.let { ERROR_TYPE_KEY to it })
        fitToEntryBudget(values, sinkTexts = listOfNotNull(error), sinkKeys, Destination.Breadcrumb)
    }

    private val forLog by lazy {
        val exceptionMessage = entry.throwable?.messageOrPlaceholder()?.let { "exception.message" to it }
        val sinkKeys = listOfNotNull(
            TAG_KEY to entry.tag,
            errorType?.let { ERROR_TYPE_KEY to it },
            exceptionType?.let { "exception.type" to it },
            userId?.let { "user.id" to it },
        )
        fitToEntryBudget(values, sinkTexts = listOfNotNull(error, exceptionMessage), sinkKeys, Destination.Log)
    }

    /** The promoted fields, then the sink's own tags, so a field of the same name gives way. */
    val eventTags: Map<String, String>
        get() = buildMap {
            promotedTags.forEach { key -> forEvent.fields[key]?.let { put(key, it.toString().toTagValue()) } }
            put(TAG_KEY, entry.tag)
            errorType?.let { put(ERROR_TYPE_KEY, it) }
        }

    /** The fields as text, then the sink's own keys, so a field of the same name gives way. */
    val eventExtras: Map<String, String>
        get() = buildMap {
            forEvent.fields.forEach { (key, value) -> put(key, value.toString()) }
            putAll(forEvent.sinkValues)
        }

    /**
     * How Sentry groups this entry's event into an issue, or null to group it by its throwable's stack. A message
     * event carries a thread dump with no frames of the app, so without a fingerprint unrelated ones merge into one.
     */
    val fingerprint: List<String>?
        get() = if (entry.throwable == null) listOf(entry.tag, errorType ?: entry.message) else null

    /** The fields, and the sink's own keys after them. */
    val breadcrumbData: Map<String, Any>
        get() = buildMap {
            putAll(forBreadcrumb.fields)
            putAll(forBreadcrumb.sinkValues)
        }

    /** The fields, typed where Sentry Logs has the type, and the sink's own keys after them. */
    val logAttributes: SentryAttributes
        get() = SentryAttributes.empty().apply {
            forLog.fields.forEach { (key, value) ->
                when (value) {
                    is String -> this[key] = value
                    is Int -> this[key] = value
                    is Long -> this[key] = value
                    is Short -> this[key] = value.toInt()
                    is Byte -> this[key] = value.toInt()
                    is Float -> this[key] = value
                    is Double -> this[key] = value
                    is Boolean -> this[key] = value
                    else -> this[key] = value.toString()
                }
            }
            forLog.sinkValues.forEach { (key, value) -> this[key] = value }
        }
}

private const val TAG_KEY = "tag"
private const val ERROR_TYPE_KEY = "error.type"

/** The error's `errorType`, or its class's simple name when that getter throws, so the entry still goes out. */
internal fun ResultError.errorTypeOrClassName(): String = try {
    errorType
} catch (_: Throwable) {
    // ResultError's default, spelled out: the getter that threw is an override, so the default is out of reach.
    this::class.simpleName ?: "ResultError"
}

/** The throwable's message, or a placeholder when its getter throws, so the entry still goes out. */
private fun Throwable.messageOrPlaceholder(): String? = try {
    message
} catch (e: Throwable) {
    "<message threw ${e::class.simpleName}>"
}

// Sentry keeps a tag value of at most 200 characters; counting UTF-16 units never counts fewer than Sentry does.
private const val TAG_VALUE_CHARS = 200

/** This text as a tag value Sentry keeps: cut to [TAG_VALUE_CHARS] ending in `…` when longer. */
private fun String.toTagValue(): String {
    if (length <= TAG_VALUE_CHARS) return this
    val end = TAG_VALUE_CHARS - 1
    return substring(0, if (this[end - 1].isHighSurrogate()) end - 1 else end) + "…"
}
