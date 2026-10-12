package com.fromwau.kern.logger.async

// Runs on a normal exit and on an uncaught exception alike, since both end the JVM through its shutdown hooks.
internal actual fun onProcessExit(flush: () -> Unit) {
    try {
        Runtime.getRuntime().addShutdownHook(Thread(flush, "kern-logger-flush"))
    } catch (_: IllegalStateException) {
        // The JVM is already shutting down, and nothing logged from here on outlives it anyway.
    }
}
