package com.fromwau.kern.logger.test

import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.fail
import kotlin.time.Duration

/** What a test sink received: all of it as a snapshot, and each item once to whoever waits for it. */
internal class Received<T : Any> {
    // One lock for both, so the snapshot and the channel see the items of two loggers in the same order.
    private val lock = reentrantLock()
    private val all = ArrayList<T>()
    private val unseen = Channel<T>(Channel.UNLIMITED)

    val flow: Flow<T> = unseen.receiveAsFlow()

    val snapshot: List<T>
        get() = lock.withLock { all.toList() }

    fun add(item: T) {
        lock.withLock {
            all += item
            val _ = unseen.trySend(item)
        }
    }

    // On Dispatchers.Default, so the timeout runs in real time even under runTest's virtual clock.
    suspend fun await(timeout: Duration, failure: () -> String): T =
        withContext(Dispatchers.Default) { withTimeoutOrNull(timeout) { unseen.receive() } } ?: fail(failure())
}
