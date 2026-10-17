package com.fromwau.kern.logger.sentry

import io.sentry.Breadcrumb
import io.sentry.IScope
import io.sentry.IScopes
import io.sentry.ScopeCallback
import io.sentry.ScopeType
import io.sentry.Sentry
import io.sentry.SentryAttributes
import io.sentry.SentryEvent
import io.sentry.SentryInstantDate
import io.sentry.SentryLevel
import io.sentry.SentryLogLevel
import io.sentry.logger.SentryLogParameters
import io.sentry.protocol.Message
import io.sentry.protocol.User
import java.util.Date
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.toJavaInstant
import io.sentry.kotlin.multiplatform.SentryLevel as KmpSentryLevel
import io.sentry.kotlin.multiplatform.log.SentryLogLevel as KmpSentryLogLevel

// sentry-java directly: the multiplatform SDK can set neither a fingerprint nor the time an entry was logged.
internal actual fun captureEvent(sent: SentryEntry) {
    val event = SentryEvent(sent.entry.throwable).apply {
        timestamp = Date(sent.entry.timestamp.toEpochMilliseconds())
        if (sent.entry.throwable == null) message = Message().apply { formatted = sent.entry.message }
    }
    val describe = ScopeCallback { scope -> scope.describe(sent) }
    val scopes = rootScopes()
    if (scopes != null) scopes.captureEvent(event, describe) else Sentry.captureEvent(event, describe)
}

/**
 * The root scopes, which carry only what the app set there, rather than the scope the sink's thread kept from an
 * earlier Sentry.init or the app's own use: a fresh fork of them on the JVM, the root scopes themselves in Android's
 * global hub mode. Null on a sentry-java without the fork, said once on stderr.
 */
private fun rootScopes(): IScopes? {
    val fork = forkRootScopes.get() ?: return null
    return try {
        fork()
    } catch (e: LinkageError) {
        if (forkRootScopes.compareAndSet(fork, null)) {
            System.err.println(
                "kern.logger.sentry: this sentry-java has no Sentry.forkedRootScopes (${e::class.simpleName}); " +
                    "events and logs now use the current thread's scope",
            )
        }
        null
    }
}

// sentry-java marks Sentry.forkedRootScopes internal; a test swaps this to stand in for a release without it.
internal val forkRootScopes = AtomicReference<(() -> IScopes)?>(::forkSentryRootScopes)

internal fun forkSentryRootScopes(): IScopes = Sentry.forkedRootScopes("kern")

private fun IScope.describe(sent: SentryEntry) {
    level = sent.entry.level.toSentryLevel().toJava()
    sent.eventTags.forEach { (key, value) -> setTag(key, value) }
    sent.eventExtras.forEach { (key, value) -> setExtra(key, value) }
    sent.fingerprint?.let { fingerprint = it }
    sent.userId?.let { user = User().apply { id = it } }
}

// On the JVM each thread has its own scope, so only the global one reaches events the app captures on its threads.
internal actual fun addBreadcrumb(sent: SentryEntry) {
    val breadcrumb = Breadcrumb(Date(sent.entry.timestamp.toEpochMilliseconds())).apply {
        message = sent.entry.message
        level = sent.entry.level.toSentryLevel().toJava()
        category = sent.entry.tag
        sent.breadcrumbData.forEach { (key, value) -> setData(key, value) }
    }
    Sentry.configureScope(ScopeType.GLOBAL) { scope -> scope.addBreadcrumb(breadcrumb) }
}

internal actual fun sendLog(sent: SentryEntry) {
    val attributes = SentryAttributes.fromMap(sent.logAttributes.mapValues { (_, attribute) -> attribute.value })
    val parameters = SentryLogParameters.create(SentryInstantDate(sent.entry.timestamp.toJavaInstant()), attributes)
    val logger = logScopes(sent.userId)?.logger() ?: Sentry.logger()
    // No arguments, so sentry-java takes the message as it is rather than as a format string.
    logger.log(sent.entry.level.toSentryLogLevel().toJava(), parameters, sent.entry.message)
}

/**
 * The scopes a log goes out on: with [userId], a fork whose current scope names that user, since sentry-java fills a
 * log's `user.id` from the scope over any attribute. Null for the current thread's scope.
 */
private fun logScopes(userId: String?): IScopes? {
    val scopes = rootScopes()
    if (userId == null) return scopes
    // Forked again even from a fresh fork: on Android forkedRootScopes hands back the app's own scopes.
    val forked = scopes?.forkedCurrentScope("kern") ?: Sentry.forkedCurrentScope("kern")
    forked.configureScope(ScopeType.CURRENT) { scope -> scope.user = User().apply { id = userId } }
    return forked
}

// The global scope outlives Sentry.close() and the next init replays it; the multiplatform SDK cannot reach it.
internal actual fun clearSinkBreadcrumbs() {
    Sentry.getGlobalScope().clearBreadcrumbs()
}

private fun KmpSentryLevel.toJava(): SentryLevel = when (this) {
    KmpSentryLevel.DEBUG -> SentryLevel.DEBUG
    KmpSentryLevel.INFO -> SentryLevel.INFO
    KmpSentryLevel.WARNING -> SentryLevel.WARNING
    KmpSentryLevel.ERROR -> SentryLevel.ERROR
    KmpSentryLevel.FATAL -> SentryLevel.FATAL
}

private fun KmpSentryLogLevel.toJava(): SentryLogLevel = when (this) {
    KmpSentryLogLevel.TRACE -> SentryLogLevel.TRACE
    KmpSentryLogLevel.DEBUG -> SentryLogLevel.DEBUG
    KmpSentryLogLevel.INFO -> SentryLogLevel.INFO
    KmpSentryLogLevel.WARN -> SentryLogLevel.WARN
    KmpSentryLogLevel.ERROR -> SentryLogLevel.ERROR
    KmpSentryLogLevel.FATAL -> SentryLogLevel.FATAL
}
