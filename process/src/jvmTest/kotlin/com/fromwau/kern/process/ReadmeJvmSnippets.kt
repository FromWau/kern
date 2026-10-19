package com.fromwau.kern.process

import com.fromwau.kern.result.onError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

// The README's JVM samples, compiled here so they keep compiling without warnings. Nothing calls them.

private fun processNextJob() = Unit

private fun stoppingAWorkerLoop() {
    val stopping = AtomicBoolean(false)
    val stopped = CountDownLatch(1)
    onShutdownRequest {
        stopping.set(true)
        stopped.await()
    }.onError { println("shutdown requests are not handled: $it") }
    try {
        while (!stopping.get()) processNextJob()
    } finally {
        stopped.countDown()
    }
}
