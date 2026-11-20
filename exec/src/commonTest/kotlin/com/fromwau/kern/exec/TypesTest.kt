package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import kotlin.test.Test
import kotlin.test.assertEquals

class TypesTest {
    @Test
    fun `a normal exit is its status`() = assertEquals(3, exitCodeOf(exitStatus = 3, signal = null))

    @Test
    fun `a death by signal N is 128 plus N`() {
        assertEquals(143, exitCodeOf(exitStatus = null, signal = 15))
        assertEquals(137, exitCodeOf(exitStatus = null, signal = 9))
    }

    @Test
    fun `each named signal has its Linux number`() = assertEquals(
        listOf(15, 9, 2, 1, 3, 10),
        listOf(
            Signal.Terminate,
            Signal.Kill,
            Signal.Interrupt,
            Signal.Hangup,
            Signal.Quit,
            Signal.Number(10),
        ).map { it.number },
    )

    @Test
    fun `an empty argv is EmptyCommand`() =
        assertEquals(Err(ExecError.EmptyCommand), SystemStarter.start(Command(emptyList())))
}
