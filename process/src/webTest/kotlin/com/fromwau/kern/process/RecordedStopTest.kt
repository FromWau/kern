package com.fromwau.kern.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordedStopTest {
    @Test
    fun theFirstRequestIsRecordedAndSetsTheExitCodeAtExit() {
        val stop = RecordedStop(exitCode = 0)

        assertNull(stop.onRequest(ShutdownRequest.Terminate))
        assertEquals(ShutdownRequest.Terminate, stop.request)
        assertEquals(0, stop.exitCodeAtExit())
    }

    @Test
    fun aSecondRequestExitsWithItsOwnCodeAndLeavesItAtExit() {
        val stop = RecordedStop(exitCode = null)
        assertNull(stop.onRequest(ShutdownRequest.Terminate))

        assertEquals(129, stop.onRequest(ShutdownRequest.Hangup))
        assertNull(stop.exitCodeAtExit())
    }

    @Test
    fun beforeARequestTheExitCodeIsLeftAlone() {
        assertNull(RecordedStop(exitCode = 0).exitCodeAtExit())
    }
}
