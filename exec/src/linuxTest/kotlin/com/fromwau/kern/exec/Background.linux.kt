package com.fromwau.kern.exec

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.native.concurrent.waitForMultipleFutures
import kotlin.time.Duration

@OptIn(ObsoleteWorkersApi::class)
actual fun <T : Any> inBackground(block: () -> T): (timeout: Duration) -> T? {
    val worker = Worker.start()
    val future = worker.execute(TransferMode.SAFE, { block }) { it() }
    return { timeout ->
        if (waitForMultipleFutures(listOf(future), timeout.inWholeMilliseconds.toInt()).isEmpty()) {
            null
        } else {
            val result = future.result
            val _ = worker.requestTermination(processScheduledJobs = false)
            result
        }
    }
}
