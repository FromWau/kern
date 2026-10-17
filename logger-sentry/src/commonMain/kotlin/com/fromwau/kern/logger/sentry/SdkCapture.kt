package com.fromwau.kern.logger.sentry

import io.sentry.kotlin.multiplatform.Scope
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import io.sentry.kotlin.multiplatform.protocol.User

/** Captures [sent] through the multiplatform SDK alone, where it does not run on sentry-java. */
internal fun captureEventWithSdk(sent: SentryEntry) {
    val throwable = sent.entry.throwable
    if (throwable == null) {
        Sentry.captureMessage(sent.entry.message) { scope -> scope.describe(sent) }
    } else {
        Sentry.captureException(throwable) { scope -> scope.describe(sent) }
    }
}

private fun Scope.describe(sent: SentryEntry) {
    level = sent.entry.level.toSentryLevel()
    sent.eventTags.forEach { (key, value) -> setTag(key, value) }
    sent.eventExtras.forEach { (key, value) -> setExtra(key, value) }
    sent.userId?.let { user = User(id = it) }
}

/** Adds [sent] as a breadcrumb through the multiplatform SDK alone. */
internal fun addBreadcrumbWithSdk(sent: SentryEntry) {
    val breadcrumb = Breadcrumb(
        level = sent.entry.level.toSentryLevel(),
        message = sent.entry.message,
        category = sent.entry.tag,
    )
    sent.breadcrumbData.forEach { (key, value) -> breadcrumb.setData(key, value) }
    Sentry.addBreadcrumb(breadcrumb)
}

/** Sends [sent] to Sentry Logs through the multiplatform SDK alone. */
internal fun sendLogWithSdk(sent: SentryEntry) {
    // The SDK reads the body as a template in which "%%" stands for "%", so each "%" is doubled to come out as logged.
    val body = sent.entry.message.replace("%", "%%")
    Sentry.logger.log(sent.entry.level.toSentryLogLevel(), body) { putAll(sent.logAttributes) }
}

/** Clears the current scope's breadcrumbs through the multiplatform SDK alone. */
internal fun clearBreadcrumbsWithSdk() {
    Sentry.configureScope { scope -> scope.clearBreadcrumbs() }
}
