@file:OptIn(ExperimentalNativeApi::class)

package com.fromwau.kern.exec.coroutines

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds

class CancelledDetachNativeTest {
    @Test
    fun `a throw from detach reaches the unhandled exception hook when a timeout cancels awaitExit`() = runBlocking {
        val again = IllegalStateException("a bug in detach")
        val reached = mutableListOf<Throwable>()
        val before = setUnhandledExceptionHook { reached += it }
        try {
            val child = DetachThrowingChild(again) { null }

            assertEquals(null, withTimeoutOrNull(50.milliseconds) { child.awaitExit() })
        } finally {
            val _ = setUnhandledExceptionHook(before)
        }
        assertEquals(listOf<Throwable>(again), reached)
    }
}
