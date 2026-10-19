package com.fromwau.kern.process.test

import com.fromwau.kern.process.ShutdownError
import com.fromwau.kern.process.ShutdownRequest
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class FakeShutdownSourceTest {
    @Test
    fun aRequestRunsTheInstalledHandlerAndRecordsItsExitCode() {
        val source = FakeShutdownSource()
        val handled = mutableListOf<ShutdownRequest>()
        assertFalse(source.installed)
        assertNull(source.exitCode)

        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest { handled += it })
        assertTrue(source.installed)
        assertNull(source.exitCode)

        source.request(ShutdownRequest.Terminate)
        assertEquals(listOf(ShutdownRequest.Terminate), handled)
        assertEquals(143, source.exitCode)
        assertNull(source.thrown)
    }

    @Test
    fun aSecondInstallIsAlreadyInstalled() {
        val source = FakeShutdownSource()

        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest {})
        assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.AlreadyInstalled), source.onShutdownRequest {})
    }

    @Test
    fun failWithFailsTheNextInstallOnlyAndInstallsNothing() {
        val source = FakeShutdownSource()
        source.failWith(ShutdownError.OutOfResources)

        assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.OutOfResources), source.onShutdownRequest {})
        assertFalse(source.installed)
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest {})
        assertTrue(source.installed)
    }

    @Test
    fun aSecondRequestRecordsItsOwnCodeWithoutRunningTheHandlerAgain() {
        val source = FakeShutdownSource()
        var calls = 0
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest { calls++ })

        source.request(ShutdownRequest.Terminate)
        source.request(ShutdownRequest.Hangup)

        assertEquals(1, calls)
        assertEquals(129, source.exitCode)
    }

    @Test
    fun aHandlerThatThrowsIsKeptInThrownAndTheExitCodeStillSet() {
        val source = FakeShutdownSource()
        val failure = IllegalStateException("the handler failed")
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest { throw failure })

        source.request(ShutdownRequest.Interrupt)

        assertSame(failure, source.thrown)
        assertEquals(130, source.exitCode)
    }

    @Test
    fun aRequestWithNoHandlerInstalledRecordsItsCodeAsTheDefaultActionWould() {
        val source = FakeShutdownSource()

        source.request(ShutdownRequest.Interrupt)

        assertEquals(130, source.exitCode)
        assertNull(source.thrown)
    }

    @Test
    fun anExitCodeGivenAtInstallIsRecordedForTheFirstRequest() {
        val source = FakeShutdownSource()
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest(exitCode = 0) {})

        source.request(ShutdownRequest.Terminate)

        assertEquals(0, source.exitCode)
    }

    @Test
    fun aLaterRequestRecordsItsOwnCodeEvenWithAnExitCodeGivenAtInstall() {
        val source = FakeShutdownSource()
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest(exitCode = 0) {})

        source.request(ShutdownRequest.Terminate)
        source.request(ShutdownRequest.Hangup)

        assertEquals(129, source.exitCode)
    }

    @Test
    fun aHandlerThatThrowsRecordsTheRequestsCodeEvenWithAnExitCodeGivenAtInstall() {
        val source = FakeShutdownSource()
        val failure = IllegalStateException("the handler failed")
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest(exitCode = 0) { throw failure })

        source.request(ShutdownRequest.Terminate)

        assertSame(failure, source.thrown)
        assertEquals(143, source.exitCode)
    }

    @Test
    fun anExitCodeOutside0To255IsRejectedAndInstallsNothing() {
        val source = FakeShutdownSource()

        val error = assertFailsWith<IllegalArgumentException> {
            fail("installed: ${source.onShutdownRequest(exitCode = 256) {}}")
        }

        assertEquals("exitCode must be in 0..255, was 256", error.message)
        assertFalse(source.installed)
    }

    @Test
    fun aSecondRequestBeforeTheHandlerThrowsKeepsItsOwnCode() {
        val source = FakeShutdownSource()
        assertEquals<EmptyResult<ShutdownError>>(
            Ok(Unit),
            source.onShutdownRequest(exitCode = 0) {
                source.request(ShutdownRequest.Hangup)
                error("the handler failed")
            },
        )

        source.request(ShutdownRequest.Terminate)

        assertEquals(129, source.exitCode)
    }

    @Test
    fun aStopRecordsTheFirstRequestAndItsExitCode() {
        val source = FakeShutdownSource()
        val stop = assertNotNull(source.stopOnShutdownRequest().getOrNull())
        assertTrue(source.installed)
        assertFalse(stop.requested)
        assertNull(stop.request)

        source.request(ShutdownRequest.Terminate)

        assertTrue(stop.requested)
        assertEquals(ShutdownRequest.Terminate, stop.request)
        assertEquals(143, source.exitCode)
    }

    @Test
    fun aStopRecordsTheExitCodeGivenAtInstallAndALaterRequestItsOwn() {
        val source = FakeShutdownSource()
        val stop = assertNotNull(source.stopOnShutdownRequest(exitCode = 0).getOrNull())

        source.request(ShutdownRequest.Terminate)
        assertEquals(0, source.exitCode)

        source.request(ShutdownRequest.Hangup)
        assertEquals(129, source.exitCode)
        assertEquals(ShutdownRequest.Terminate, stop.request)
    }

    @Test
    fun aStopAndAHandlerShareTheOneInstall() {
        val stopFirst = FakeShutdownSource()
        assertTrue(stopFirst.stopOnShutdownRequest() is Result.Success)
        assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.AlreadyInstalled), stopFirst.onShutdownRequest {})

        val handlerFirst = FakeShutdownSource()
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), handlerFirst.onShutdownRequest {})
        assertEquals(Err(ShutdownError.AlreadyInstalled), handlerFirst.stopOnShutdownRequest())
    }

    @Test
    fun aStopWithAnExitCodeOutside0To255IsRejected() {
        val source = FakeShutdownSource()

        val error = assertFailsWith<IllegalArgumentException> {
            fail("installed: ${source.stopOnShutdownRequest(exitCode = 256)}")
        }

        assertEquals("exitCode must be in 0..255, was 256", error.message)
        assertFalse(source.installed)
    }

    @Test
    fun failWithFailsTheNextStopToo() {
        val source = FakeShutdownSource()
        source.failWith(ShutdownError.Unsupported)

        assertEquals(Err(ShutdownError.Unsupported), source.stopOnShutdownRequest())
        assertFalse(source.installed)
    }

    @Test
    fun aSecondInstallIsAlreadyInstalledEvenWithAFailureSet() {
        val source = FakeShutdownSource()
        assertEquals<EmptyResult<ShutdownError>>(Ok(Unit), source.onShutdownRequest {})
        source.failWith(ShutdownError.OutOfResources)

        assertEquals<EmptyResult<ShutdownError>>(Err(ShutdownError.AlreadyInstalled), source.onShutdownRequest {})
        assertEquals(Err(ShutdownError.AlreadyInstalled), source.stopOnShutdownRequest())
    }
}
