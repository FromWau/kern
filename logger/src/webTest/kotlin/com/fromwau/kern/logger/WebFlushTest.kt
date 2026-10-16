package com.fromwau.kern.logger

import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class WebFlushTest {
    private val logger = testLogger()

    @AfterTest
    fun cleanup() {
        logger.removeAll()
    }

    @Test
    fun `flush cannot wait on one thread but still ends the hold`() = runTest {
        val sink = TestSink()
        val _ = logger.addOrFail(sink)
        logger.holdUntilConfigured()
        logger.tag("boot").i { "held" }

        assertEquals(Err(FlushError.CannotWait), logger.flush())
        assertEquals(Ok(Flushed.FullyWritten), logger.awaitFlushed())
        assertEquals(listOf("held"), sink.messages)
    }
}
