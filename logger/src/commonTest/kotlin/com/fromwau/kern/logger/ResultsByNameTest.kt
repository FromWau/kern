package com.fromwau.kern.logger

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import kotlin.test.Test
import kotlin.test.assertEquals

class ResultsByNameTest {
    private val full: SinkError = SinkError.Dropped(Thrown(IllegalStateException("disk full")))

    @Test
    fun `two sinks of a name timed out are one TimedOut with both counts`() {
        val pending = pendingByName(listOf("file" to Pending.TimedOut(2), "file" to Pending.TimedOut(3)))

        assertEquals(mapOf("file" to Pending.TimedOut(5)), pending)
    }

    @Test
    fun `two sinks of a name that gave up are one GaveUp with both counts and the first error`() {
        val pending = pendingByName(listOf("file" to Pending.GaveUp(2, refused), "file" to Pending.GaveUp(3, full)))

        assertEquals(mapOf("file" to Pending.GaveUp(5, refused)), pending)
    }

    @Test
    fun `a sink that gave up and one of the same name that timed out are one GaveUp with its error`() {
        val gaveUpFirst = pendingByName(listOf("file" to Pending.GaveUp(2, refused), "file" to Pending.TimedOut(3)))
        val timedOutFirst = pendingByName(listOf("file" to Pending.TimedOut(3), "file" to Pending.GaveUp(2, refused)))

        assertEquals(mapOf("file" to Pending.GaveUp(5, refused)), gaveUpFirst)
        assertEquals(mapOf("file" to Pending.GaveUp(5, refused)), timedOutFirst)
    }

    @Test
    fun `the losses of every sink of a name add up and other names stay apart in the order they came`() {
        val lost = sumByName(listOf("debug" to 2L, "file" to 1L, "debug" to 3L, "file" to 4L, "debug" to 5L))

        assertEquals(mapOf("debug" to 10L, "file" to 5L), lost)
        assertEquals(listOf("debug", "file"), lost.keys.toList())
    }

    @Test
    fun `a name's close errors keep one TimedOut and one Lost summed and every other error once in order`() {
        val closed = closeResult(
            listOf(
                "file" to listOf(SinkError.TimedOut(2)),
                "console" to emptyList(),
                "file" to listOf(full, SinkError.TimedOut(1), SinkError.Lost(3)),
                "file" to listOf(SinkError.ClosedAlready, SinkError.Lost(4)),
                "file" to listOf(full, SinkError.ClosedAlready),
            ),
        )

        val file = listOf(SinkError.TimedOut(3), full, SinkError.ClosedAlready, SinkError.Lost(7))
        assertEquals(Err(CloseError(mapOf("file" to file))), closed)
    }

    @Test
    fun `close errors list each name once in the order the names first came`() {
        val closed = closeResult(
            listOf(
                "file" to listOf(SinkError.Lost(1)),
                "debug" to listOf(SinkError.Lost(2)),
                "file" to listOf(SinkError.Lost(3)),
            ),
        )

        val expected = mapOf("file" to listOf(SinkError.Lost(4)), "debug" to listOf(SinkError.Lost(2)))
        assertEquals(Err(CloseError(expected)), closed)
        assertEquals(listOf("file", "debug"), closed.assertError<CloseError>().errors.keys.toList())
    }

    @Test
    fun `close is Ok when no sink of any name has an error`() {
        assertEquals(Ok(Unit), closeResult(listOf("file" to emptyList(), "file" to emptyList())))
    }
}
