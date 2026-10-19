package com.fromwau.kern.process

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.Test
import kotlin.test.assertEquals

class ShutdownNodeTest {
    // Installs into the Node process running the tests: Ctrl-C still ends it, with 130.
    @Test
    fun aSecondInstallIsAlreadyInstalled() {
        assertEquals(Ok(Unit), onShutdownRequest {})
        assertEquals(Err(ShutdownError.AlreadyInstalled), onShutdownRequest {})
    }
}
