package com.fromwau.kern.terminal

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_DIRECTORY
import platform.posix.O_NONBLOCK
import platform.posix.O_RDONLY
import platform.posix.O_WRONLY
import platform.posix.close
import platform.posix.dup
import platform.posix.dup2
import platform.posix.fcntl
import platform.posix.open
import platform.posix.pipe
import platform.posix.usleep
import platform.posix.write
import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The real standard input: each test puts a descriptor of its own on 0 and gives the old one back after. */
@OptIn(ExperimentalForeignApi::class, ObsoleteWorkersApi::class)
class StdinTest {

    private fun withStdin(fd: Int, block: () -> Unit) {
        val saved = dup(STDIN)
        check(saved >= 0 && dup2(fd, STDIN) == STDIN)
        close(fd)
        try {
            block()
        } finally {
            dup2(saved, STDIN)
            close(saved)
        }
    }

    /** A pipe holding [bytes] whose writer has closed, so a read finds them and then the end. */
    private fun pipeOf(bytes: ByteArray): Int = memScoped {
        val ends = allocArray<IntVar>(2)
        check(pipe(ends) == 0)
        val written = bytes.usePinned { write(ends[1], it.addressOf(0), bytes.size.convert()) }
        check(written.toInt() == bytes.size)
        close(ends[1])
        ends[0]
    }

    @Test
    fun `standard input comes through byte for byte`() {
        val bytes = byteArrayOf(
            0xEF.toByte(),
            0xBB.toByte(),
            0xBF.toByte(),
            'a'.code.toByte(),
            0x0D,
            0x0A,
            0xFF.toByte(),
        )

        withStdin(pipeOf(bytes)) {
            assertContentEquals(bytes, defaultTerminal().readIn().assertSuccess())
        }
    }

    @Test
    fun `standard input past maxBytes is TooLarge`() {
        withStdin(pipeOf("12345".encodeToByteArray())) {
            val error = defaultTerminal().readIn(maxBytes = 4).assertError<ReadError.TooLarge>()
            assertEquals(ReadError.TooLarge(4, 5), error)
        }
    }

    @Test
    fun `a directory as standard input is IsADirectory`() {
        val directory = open("/", O_RDONLY or O_DIRECTORY)
        check(directory >= 0)

        withStdin(directory) {
            defaultTerminal().readIn().assertError<ReadError.IsADirectory>()
        }
    }

    @Test
    fun `a standard input the system will not read is Refused with its message`() {
        val writeOnly = open("/dev/null", O_WRONLY)
        check(writeOnly >= 0)

        withStdin(writeOnly) {
            val error = defaultTerminal().readIn().assertError<ReadError.Refused>()
            assertEquals(ReadError.Refused("Bad file descriptor"), error)
        }
    }

    @Test
    fun `a non-blocking standard input waits for its writer`() {
        val (reader, writer) = memScoped {
            val ends = allocArray<IntVar>(2)
            check(pipe(ends) == 0)
            ends[0] to ends[1]
        }
        check(fcntl(reader, F_SETFL, fcntl(reader, F_GETFL) or O_NONBLOCK) == 0)
        val worker = Worker.start()
        val written = worker.execute(TransferMode.SAFE, { writer }) { fd ->
            usleep(100_000u)
            val bytes = "late".encodeToByteArray()
            val count = bytes.usePinned { write(fd, it.addressOf(0), bytes.size.convert()) }
            close(fd)
            count.toInt() == bytes.size
        }
        try {
            withStdin(reader) {
                assertContentEquals("late".encodeToByteArray(), defaultTerminal().readIn().assertSuccess())
            }
        } finally {
            check(written.result)
            worker.requestTermination().result
        }
    }
}
