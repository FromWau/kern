package com.fromwau.kern.terminal

import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertTrue

// While a block runs on one thread, another thread's outermost call to afterWrites still runs its block at once.
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private fun assertThreadsDoNotWaitForEachOther(terminal: Terminal) {
    val first = newSingleThreadContext("first")
    val second = newSingleThreadContext("second")
    try {
        runBlocking {
            val blockRunning = CompletableDeferred<Unit>()
            val otherThreadAnswered = CompletableDeferred<Boolean>()
            launch(first) {
                terminal.afterWrites {
                    blockRunning.complete(Unit)
                    runBlocking { otherThreadAnswered.await() }
                }
            }
            blockRunning.await()

            withContext(second) {
                var ranAtOnce = false
                terminal.afterWrites { ranAtOnce = true }
                otherThreadAnswered.complete(ranAtOnce)
            }

            assertTrue(otherThreadAnswered.await())
        }
    } finally {
        first.close()
        second.close()
    }
}

class AfterWritesThreadTest {

    @Test
    fun `a block running on one thread does not hold back the default afterWrites on another`() {
        assertThreadsDoNotWaitForEachOther(RecordingTerminal())
    }

    @Test
    fun `a block running on one thread does not hold back the platform terminal on another`() {
        assertThreadsDoNotWaitForEachOther(defaultTerminal())
    }
}
