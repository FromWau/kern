package com.fromwau.kern.exec

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.time.Duration

actual fun <T : Any> inBackground(block: () -> T): (timeout: Duration) -> T? {
    val result = CompletableFuture<T>()
    Thread {
        try {
            result.complete(block())
        } catch (failure: Throwable) {
            result.completeExceptionally(failure)
        }
    }.apply { isDaemon = true }.start()
    return { timeout ->
        try {
            result.get(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        }
    }
}
