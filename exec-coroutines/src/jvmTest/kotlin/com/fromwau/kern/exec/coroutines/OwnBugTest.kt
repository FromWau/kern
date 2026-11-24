package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.assertSuccess
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds

/** A bug in kern's JVM child reaches the uncaught handler of its thread whatever a cancelled caller does. */
class OwnBugTest {
    private val bug = IllegalStateException("a bug in kern")

    /** The thread the probe threw on. */
    private val thrower = AtomicReference<Thread?>(null)

    /** What reached the uncaught exception handler of the thread the probe threw on. */
    private val uncaught = AtomicReference<Throwable?>(null)

    @Test
    fun `a throw while reading reaches the uncaught handler when a timeout cancels awaitExit`() = runBlocking {
        var thrown = false
        val probe: (String) -> Unit = {
            if (it == "Read" && !thrown) {
                thrown = true
                throwOnThisThread()
            }
        }
        val child = probing(probe) { SystemStarter.start(sh("echo hi")).assertSuccess() }
        awaitThrower()
        // A busy dispatcher, so no read is waiting when the timeout cancels the call.
        val busy = Executors.newSingleThreadExecutor()
        busy.execute { Thread.sleep(300) }

        busy.asCoroutineDispatcher().use { dispatcher ->
            assertNull(withTimeoutOrNull(50.milliseconds) { child.awaitExit(dispatcher) })
        }
        assertSame(bug, uncaught.get())
    }

    @Test
    fun `a throw while reading reaches the uncaught handler when a timeout cancels run`() = runBlocking {
        val cancelled = CountDownLatch(1)
        val probe: (String) -> Unit = {
            if (it == "Read" && thrower.get() == null) {
                cancelled.await()
                throwOnThisThread()
            }
        }

        assertNull(probing(probe) { withTimeoutOrNull(100.milliseconds) { SystemStarter.run(sh("echo hi; sleep 5")) } })
        cancelled.countDown()
        awaitThrower()
        assertSame(bug, uncaught.get())
    }

    private fun throwOnThisThread(): Nothing {
        val thread = Thread.currentThread()
        thread.setUncaughtExceptionHandler { _, reached -> uncaught.set(reached) }
        thrower.set(thread)
        throw bug
    }

    /** Waits for the probe to throw, then for the thread it threw on to finish what it does after the throw. */
    private fun awaitThrower() {
        val deadline = System.nanoTime() + 5_000_000_000
        while (thrower.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        val thread = checkNotNull(thrower.get()) { "the probe never threw" }
        thread.join(5_000)
        assertFalse(thread.isAlive, "the thread that threw still runs")
    }
}

/**
 * Runs [start] with [probe] called at each step of the children it starts, by the step's name. The probe is internal
 * to kern:exec, so it is reached by reflection.
 */
private inline fun <T> probing(noinline probe: (String) -> Unit, start: () -> T): T {
    @Suppress("UNCHECKED_CAST")
    val testProbe = Class.forName("com.fromwau.kern.exec.FfmChildKt")
        .getDeclaredField("testProbe")
        .apply { isAccessible = true }
        .get(null) as AtomicReference<((Enum<*>) -> Unit)?>
    testProbe.set { step -> probe(step.name) }
    try {
        return start()
    } finally {
        testProbe.set(null)
    }
}
