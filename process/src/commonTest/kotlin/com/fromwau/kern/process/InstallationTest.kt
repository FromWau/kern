package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.fail

class InstallationTest {
    @Test
    fun aPlatformInstallThatThrowsLetsALaterCallInstall() {
        var calls = 0
        val installation = Installation(
            installStop = { fail("installed a stop") },
            installHandler = {
                calls++
                if (calls == 1) throw IllegalStateException("the platform refused")
                Ok(Unit)
            },
            // With nothing else installing, waiting could only spin forever.
            onWait = { fail("the later call waited for an install that had already ended") },
        )

        assertFailsWith<IllegalStateException> { installation.install(exitCode = null) {} }
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), installation.install(exitCode = null) {})
    }

    @Test
    fun withoutAnExitCodeTheProcessExitsWithTheRequestsCode() {
        assertEquals(143, exitCodeAfterHandling(exitCode = null, ShutdownRequest.Terminate))
    }

    @Test
    fun systemShutdownSourceIsOneSharedObject() {
        assertSame(systemShutdownSource(), systemShutdownSource())
    }

    @Test
    fun aHandlerThatThrowsExitsWithTheRequestsCodeEvenWithAnExitCodeGivenAtInstall() {
        assertEquals(
            143,
            exitCodeAfterHandling(exitCode = 0, ShutdownRequest.Terminate) { throw IllegalStateException("handler") },
        )
    }

    @Test
    fun anExitCodeOutside0To255IsRejectedBeforeAnythingIsInstalled() {
        val installation = Installation(
            installHandler = { fail("installed despite a bad exitCode") },
            installStop = { fail("installed despite a bad exitCode") },
        )

        for (exitCode in listOf(256, -1)) {
            val error = assertFailsWith<IllegalArgumentException> {
                fail("installed: ${installation.install(exitCode) {}}")
            }
            assertEquals("exitCode must be in 0..255, was $exitCode", error.message)
        }
    }

    @Test
    fun exitCodes0And255AreAccepted() {
        assertEquals(0, exitCodeAfterHandling(exitCode = 0, ShutdownRequest.Terminate))
        assertEquals(255, exitCodeAfterHandling(exitCode = 255, ShutdownRequest.Terminate))
    }

    @Test
    fun aStopAfterAHandlerIsAlreadyInstalled() {
        val installation = Installation(
            installHandler = { Ok(Unit) },
            installStop = { fail("installed a stop after a handler") },
        )

        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), installation.install(exitCode = null) {})
        assertEquals(Err(ShutdownError.AlreadyInstalled), installation.stop(exitCode = null))
    }

    @Test
    fun aHandlerAfterAStopIsAlreadyInstalled() {
        val stop = FixedStop()
        val installation = Installation(
            installHandler = { fail("installed a handler after a stop") },
            installStop = { Ok(stop) },
        )

        assertEquals(Ok(stop), installation.stop(exitCode = null))
        assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.AlreadyInstalled), installation.install(null) {})
    }

    @Test
    fun aStopGetsTheExitCodeGivenAtInstall() {
        var given: Int? = null
        val installation = Installation(
            installHandler = { fail("installed a handler") },
            installStop = { exitCode ->
                given = exitCode
                Ok(FixedStop())
            },
        )

        assertEquals(Ok(FixedStop()), installation.stop(exitCode = 0))
        assertEquals(0, given)
    }

    @Test
    fun aStopWithAnExitCodeOutside0To255IsRejectedBeforeAnythingIsInstalled() {
        val installation = Installation(
            installHandler = { fail("installed a handler") },
            installStop = { fail("installed despite a bad exitCode") },
        )

        val error = assertFailsWith<IllegalArgumentException> {
            fail("installed: ${installation.stop(exitCode = 256)}")
        }
        assertEquals("exitCode must be in 0..255, was 256", error.message)
    }

    private data class FixedStop(
        override val requested: Boolean = false,
        override val request: ShutdownRequest? = null,
    ) : ShutdownStop

    private fun exitCodeAfterHandling(
        exitCode: Int?,
        request: ShutdownRequest,
        handler: (ShutdownRequest) -> Unit = {},
    ): Int {
        var onRequest: ((ShutdownRequest) -> Int)? = null
        val installation = Installation(
            installStop = { fail("installed a stop") },
            installHandler = { platformOnRequest ->
                onRequest = platformOnRequest
                Ok(Unit)
            },
        )
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), installation.install(exitCode, handler))
        return onRequest!!(request)
    }
}
