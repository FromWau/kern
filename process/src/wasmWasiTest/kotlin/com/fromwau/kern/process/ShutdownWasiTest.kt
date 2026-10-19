package com.fromwau.kern.process

import com.fromwau.kern.result.Err
import kotlin.test.Test
import kotlin.test.assertEquals

class ShutdownWasiTest {
    @Test
    fun wasiIsUnsupportedEveryTime() {
        assertEquals(Err(ShutdownError.Unsupported), onShutdownRequest {})
        assertEquals(Err(ShutdownError.Unsupported), onShutdownRequest {})
    }
}
