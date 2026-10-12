package com.fromwau.kern.logger.async

// Android kills a crashed app without running shutdown hooks, so the flush goes ahead of whatever handler was set.
internal actual fun onProcessExit(flush: () -> Unit) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        flush()
        previous?.uncaughtException(thread, error)
    }
}
