@file:OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class, ExperimentalAtomicApi::class)

package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select

class InstallationThreadTest {
    @Test
    fun `a call made while an install fails installs instead of answering AlreadyInstalled`() {
        val firstInstalling = CompletableDeferred<Unit>()
        val firstMayFail = CompletableDeferred<Unit>()
        val secondWaiting = CompletableDeferred<Unit>()
        val platformCalls = AtomicInt(0)
        val installation = Installation(
            installStop = { fail("installed a stop") },
            installHandler = {
                if (platformCalls.fetchAndIncrement() == 0) {
                    firstInstalling.complete(Unit)
                    runBlocking { firstMayFail.await() }
                    Err(ShutdownError.OutOfResources)
                } else {
                    Ok(Unit)
                }
            },
            onWait = { secondWaiting.complete(Unit) },
        )
        val firstThread = newSingleThreadContext("first")
        val secondThread = newSingleThreadContext("second")

        try {
            runBlocking {
                val first = async(firstThread) { installation.install(exitCode = null) {} }
                firstInstalling.await()
                val second = async(secondThread) { installation.install(exitCode = null) {} }
                select {
                    secondWaiting.onAwait {}
                    second.onAwait {}
                }
                firstMayFail.complete(Unit)

                assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.OutOfResources), first.await())
                assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), second.await())
            }
        } finally {
            firstThread.close()
            secondThread.close()
        }
    }
}
