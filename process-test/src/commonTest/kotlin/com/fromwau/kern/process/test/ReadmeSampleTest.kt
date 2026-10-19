package com.fromwau.kern.process.test

import com.fromwau.kern.process.ShutdownRequest
import com.fromwau.kern.process.ShutdownSource
import com.fromwau.kern.result.onError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Server(source: ShutdownSource) {
    var stopped = false
        private set

    init {
        source.onShutdownRequest { stopped = true }
            .onError { println("shutdown requests are not handled: $it") }
    }
}

class ReadmeSampleTest {
    @Test
    fun aTerminateRequestStopsTheServer() {
        val source = FakeShutdownSource()
        val server = Server(source)

        source.request(ShutdownRequest.Terminate)

        assertTrue(server.stopped)
        assertEquals(143, source.exitCode)
    }
}
