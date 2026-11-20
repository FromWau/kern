package com.fromwau.kern.exec

import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

class OutputTest {
    @Test
    fun `the exit code is the child's`() = assertEquals(
        listOf(0, 1, 255),
        listOf("exit 0", "exit 1", "exit 255").map { exitCode(sh(it).events()) },
    )

    @Test
    fun `a child killed by a signal exits 128 plus its number`() = assertEquals(
        listOf(143, 137),
        listOf("kill -TERM $$", "kill -KILL $$").map { exitCode(sh(it).events()) },
    )

    @Test
    fun `every write before the exit comes before Exited`() = repeat(200) { round ->
        val events = sh("printf a; printf b >&2; printf c").events()
        val beforeExit = events.subList(0, events.indexOf(ChildEvent.Exited(0)))
        assertEquals("ac", beforeExit.text(ChildStream.Stdout), "round $round: $events")
        assertEquals("b", beforeExit.text(ChildStream.Stderr), "round $round: $events")
    }

    @Test
    fun `a background job writes after Exited and before OutputClosed`() {
        val events = sh("(sleep 0.3; echo late) & echo now").events()
        assertEquals(listOf("now\n", "late\n"), events.filterIsInstance<ChildEvent.Output>().map { it.text })
        assertTrue(
            events.indexOf(ChildEvent.Exited(0)) <
                events.indexOfFirst { it == ChildEvent.Output("late\n", ChildStream.Stdout) },
        )
        assertEquals(ChildEvent.OutputClosed, events.last())
    }

    @Test
    fun `stdin bytes larger than a pipe are all read`() = assertEquals(
        "1048576\n",
        sh("wc -c").copy(stdin = Stdin.Bytes(ByteArray(MEBIBYTE))).events().text(ChildStream.Stdout),
    )

    @Test
    fun `a child that never reads its stdin leaves this process alive`() = assertEndsOnce(
        sh("exit 0").copy(stdin = Stdin.Bytes(ByteArray(MEBIBYTE))).events(),
    )

    @Test
    fun `ten MiB written before anyone reads all come in order`() {
        val size = 10 * MEBIBYTE
        val child = SystemStarter.start(sh("head -c $size /dev/zero | tr '\\0' a")).assertSuccess()
        assertTrue(ChildEvent.Exited(0) in sh("sleep 1").events())
        // Field 3 of /proc/PID/stat is the state: Z once it exited, its entry held until its events are read.
        val state = sh("cut -d' ' -f3 /proc/$1/stat", child.pid.toString()).events().text(ChildStream.Stdout).trim()
        assertEquals("Z", state, "the child had not exited before next()")

        val events = child.drain()
        val text = events.text(ChildStream.Stdout)
        assertEquals(size, text.length)
        assertTrue(text.all { it == 'a' })
        assertEndsOnce(events.filter { it !is ChildEvent.Output })
    }

    @Test
    fun `two children at once close their output separately`() {
        // The first child's stdin writer is still blocked on its pipe when the second child starts.
        val reading = SystemStarter.start(
            sh("sleep 0.5; timeout 3 cat >/dev/null; echo b").copy(stdin = Stdin.Bytes(ByteArray(MEBIBYTE))),
        ).assertSuccess()
        val started = TimeSource.Monotonic.markNow()
        val sleeping = SystemStarter.start(sh("sleep 2")).assertSuccess()

        val events = generateSequence { reading.next() }.takeWhile { it != ChildEvent.OutputClosed }.toList()
        assertTrue(started.elapsedNow() < 1.5.seconds, "the first child's output closed after ${started.elapsedNow()}")
        assertEquals("b\n", events.text(ChildStream.Stdout))
        assertEndsOnce(sleeping.drain())
    }

    @Test
    fun `a child that writes more than a pipe holds right before it exits has all of it before Exited`() =
        repeat(20) { round ->
            val events = sh("exec head -c $PAST_A_PIPE /dev/zero").events()
            val beforeExit = events.subList(0, events.indexOf(ChildEvent.Exited(0)))
            assertEquals(PAST_A_PIPE, beforeExit.text(ChildStream.Stdout).length, "round $round")
        }

    @Test
    fun `a background job that keeps writing does not hold the exit back`() {
        val folder = temporaryFolder()
        val jobPid = "$folder/job.pid"
        val child = SystemStarter.start(sh("yes & echo $! > \"$1\"; sleep 0.2; exit 0", jobPid)).assertSuccess()
        try {
            val started = TimeSource.Monotonic.markNow()
            var exited = false
            while (!exited && started.elapsedNow() < 2.seconds) exited = child.next() == ChildEvent.Exited(0)
            assertTrue(exited, "no Exited within 2 s")
        } finally {
            // The job may already be gone: where the output ends at the exit, it dies writing to a closed pipe.
            val pid = sh("cat \"$1\"", jobPid).events().text(ChildStream.Stdout).trim()
            val _ = Command(listOf("/bin/kill", "-KILL", pid)).events()
            val _ = child.drain()
            folder.removeAll()
        }
    }

    @Test
    fun `bad UTF-8 from the child is U+FFFD`() =
        assertEquals("a�b", sh("printf 'a\\377b'").events().text(ChildStream.Stdout))

    /** [events] are exactly one `Exited(0)` and one `OutputClosed`, which may come in either order. */
    private fun assertEndsOnce(events: List<ChildEvent>) {
        assertEquals(2, events.size, "$events")
        assertEquals(setOf(ChildEvent.Exited(0), ChildEvent.OutputClosed), events.toSet())
    }

    private fun exitCode(events: List<ChildEvent>): Int? =
        events.filterIsInstance<ChildEvent.Exited>().singleOrNull()?.code

    private companion object {
        const val MEBIBYTE = 1024 * 1024
        const val PAST_A_PIPE = 200_000
    }
}
