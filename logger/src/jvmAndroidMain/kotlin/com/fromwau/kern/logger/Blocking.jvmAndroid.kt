package com.fromwau.kern.logger

import kotlinx.coroutines.runBlocking

internal actual fun <T> runBlockingOrNull(block: suspend () -> T): T? = try {
    runBlocking { block() }
} catch (_: InterruptedException) {
    // runBlocking clears the flag as it throws; the caller's thread should still see it was interrupted.
    Thread.currentThread().interrupt()
    null
}
