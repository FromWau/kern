package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.time.Duration.Companion.milliseconds

/** A throw from detach while a call is cancelled reaches the uncaught handler of the thread that detached. */
class CancelledDetachJvmTest {
    private val again = IllegalStateException("a bug in detach")

    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val thread = Thread.currentThread()
    private val handlerBefore = thread.uncaughtExceptionHandler

    @BeforeTest
    fun recordUncaught() {
        thread.setUncaughtExceptionHandler { _, reached -> uncaught += reached }
    }

    @AfterTest
    fun restoreHandler() {
        thread.setUncaughtExceptionHandler(handlerBefore)
    }

    @Test
    fun `a throw from detach reaches the uncaught handler when a timeout cancels awaitExit`() = runBlocking {
        val child = DetachThrowingChild(again) { null }

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { child.awaitExit() })
        assertEquals(listOf<Throwable>(again), uncaught.distinct())
    }

    @Test
    fun `a throw from detach reaches the uncaught handler when a timeout cancels awaitExit of a blocking child`() =
        runBlocking {
            val child = DetachThrowingChild(again) { null }
            val blocking = object : Child by child {}

            assertEquals(null, withTimeoutOrNull(50.milliseconds) { blocking.awaitExit() })
            assertEquals(listOf<Throwable>(again), uncaught.distinct())
        }

    @Test
    fun `a throw from detach reaches the uncaught handler when a timeout cancels collecting events`() = runBlocking {
        val child = DetachThrowingChild(again) { null }

        assertEquals(null, withTimeoutOrNull(50.milliseconds) { child.events().collect {} })
        assertEquals(listOf<Throwable>(again), uncaught.distinct())
    }

    @Test
    fun `a throw from detach when first ends the collection reaches the uncaught handler`() = runBlocking {
        val child = DetachThrowingChild(again) { ChildEvent.OutputClosed }

        assertEquals(ChildEvent.OutputClosed, child.events().first())
        assertEquals(listOf<Throwable>(again), uncaught.distinct())
    }

    @Test
    fun `a throw from detach after a throw from the child reaches only the child's throw`() = runBlocking {
        val bug = IllegalStateException("a bug in the child")
        val child = DetachThrowingChild(again) { throw bug }

        val _ = assertFails { child.awaitExit() }
        assertEquals(emptyList(), uncaught)
    }
}
