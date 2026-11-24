package com.fromwau.kern.logger

import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalClockMingwTest {
    @Test
    fun `the failed check of the registry lookup is a zone that cannot be read`() {
        assertTrue(IllegalStateException("The current system time zone is invalid").isUnreadableSystemZone())
    }

    @Test
    fun `a cancellation is not a zone that cannot be read`() {
        assertFalse(CancellationException("cancelled").isUnreadableSystemZone())
    }
}
