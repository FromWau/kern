package com.fromwau.kern.process

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExitAfterHandlerTest {
    @Test
    fun theProcessExitsWithTheCodeTheHandlerRunGives() {
        var exited: Int? = null

        exitAfterHandler(ShutdownRequest.Terminate, onRequest = { 0 }, exit = { exited = it })

        assertEquals(0, exited)
    }

    @Test
    fun whenPrintingTheHandlersExceptionFailsTheProcessStillExitsWithTheRequestsCode() {
        var exited: Int? = null

        assertFailsWith<IllegalStateException> {
            exitAfterHandler(
                ShutdownRequest.Terminate,
                onRequest = { throw IllegalStateException("cannot print") },
                exit = { exited = it },
            )
        }

        assertEquals(143, exited)
    }
}
