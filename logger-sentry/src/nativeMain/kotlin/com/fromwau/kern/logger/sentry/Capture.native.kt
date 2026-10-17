package com.fromwau.kern.logger.sentry

internal actual fun captureEvent(sent: SentryEntry) = captureEventWithSdk(sent)

internal actual fun addBreadcrumb(sent: SentryEntry) = addBreadcrumbWithSdk(sent)

internal actual fun sendLog(sent: SentryEntry) = sendLogWithSdk(sent)

internal actual fun clearSinkBreadcrumbs() = clearBreadcrumbsWithSdk()
