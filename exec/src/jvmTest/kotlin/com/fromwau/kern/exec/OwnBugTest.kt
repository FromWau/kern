package com.fromwau.kern.exec

import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import java.io.File
import java.io.RandomAccessFile
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import org.junit.jupiter.api.Assumptions.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertSame

/**
 * How a bug in kern's own code on the JVM is reported: to the uncaught handler of the thread it happened on, and
 * unchanged to a caller of start or next, with the child still cleaned up.
 */
class OwnBugTest {
    private val bug = IllegalStateException("a bug in kern")

    /** The thread that reached the step the probe threw at. */
    private val thrower = AtomicReference<Thread?>(null)

    /** What reached the uncaught exception handler of the thread the probe threw on, in order. */
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    /** A probe that throws [bug] the first time [step] is reached. */
    private fun throwingOnceAt(step: FfmStep): (FfmStep) -> Unit {
        var thrown = false
        return {
            if (it == step && !thrown) {
                thrown = true
                throwOnThisThread(bug)
            }
        }
    }

    /** Throws [thrown], recording this thread as the [thrower] and what reaches its handler in [uncaught]. */
    private fun throwOnThisThread(thrown: Throwable): Nothing {
        val thread = Thread.currentThread()
        thread.setUncaughtExceptionHandler { _, reached -> uncaught += reached }
        thrower.compareAndSet(null, thread)
        throw thrown
    }

    /** Waits for the probe to throw, then for the thread it threw on to finish what it does after the throw. */
    private fun awaitThrower() {
        val deadline = System.nanoTime() + 5_000_000_000
        while (thrower.get() == null && System.nanoTime() < deadline) Thread.sleep(10)
        val thread = checkNotNull(thrower.get()) { "the probe never threw" }
        thread.join(5_000)
        assertFalse(thread.isAlive, "the thread that threw still runs")
    }

    @Test
    fun `a throw while starting the child reaches the uncaught handler and is rethrown unchanged from start`() {
        val probe = throwingOnceAt(FfmStep.Spawn)

        assertSame(bug, assertFails { probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))) } })
        awaitThrower()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while reading the child reaches the uncaught handler and is rethrown unchanged from next`() {
        val child = probing(throwingOnceAt(FfmStep.Read)) { SystemStarter.start(sh("echo hi")).assertSuccess() }

        assertSame(bug, assertFails { child.drain() })
        awaitThrower()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while writing the child's input reaches the uncaught handler and is rethrown unchanged from next`() {
        val command = Command(listOf("/bin/cat"), stdin = Stdin.Bytes("hi".encodeToByteArray()))
        val child = probing(throwingOnceAt(FfmStep.WriteStdin)) { SystemStarter.start(command).assertSuccess() }

        assertSame(bug, assertFails { child.drain() })
        awaitThrower()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while reading still closes the child's descriptors and reaps it`() {
        val before = openPipes()
        val child = probing(throwingOnceAt(FfmStep.Read)) { SystemStarter.start(sh("echo hi")).assertSuccess() }
        runCatching { child.drain() }
        awaitThrower()

        assertEquals(before, openPipes())
        assertFalse(File("/proc/${child.pid}").exists(), "the child was not reaped")
    }

    @Test
    fun `a throw after the child was reaped waits on its pid no more`() {
        val steps = CopyOnWriteArrayList<FfmStep>()
        val throwing = throwingOnceAt(FfmStep.Reaped)
        val probe: (FfmStep) -> Unit = {
            steps += it
            throwing(it)
        }
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        runCatching { child.drain() }
        awaitThrower()

        assertEquals(emptyList(), steps.dropWhile { it != FfmStep.Reaped }.filter { it == FfmStep.Wait })
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw after the child was detached reaches the uncaught handler of the thread it happened on`() {
        val child = probing(throwingOnceAt(FfmStep.Reaped)) { SystemStarter.start(sh("sleep 0.2")).assertSuccess() }
        child.detach()
        awaitThrower()

        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw that next never took reaches the uncaught handler and detach throws nothing`() {
        val child = probing(throwingOnceAt(FfmStep.Read)) { SystemStarter.start(sh("echo hi")).assertSuccess() }
        awaitThrower()

        child.detach()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while writing the input before a start failed reaches the uncaught handler and start answers`() {
        val probe: (FfmStep) -> Unit = {
            if (it == FfmStep.WriteStdin) throwOnThisThread(bug)
            if (it == FfmStep.Spawn) awaitThrower()
        }

        val command = unspawnable(Stdin.Bytes(byteArrayOf(1)))

        probing(probe) { SystemStarter.start(command) }.assertError<ExecError.StartError>()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while writing the input after a start failed reaches the uncaught handler of the thread`() {
        val startAnswered = CountDownLatch(1)
        val probe: (FfmStep) -> Unit = {
            if (it == FfmStep.WriteStdin) {
                startAnswered.await()
                throwOnThisThread(bug)
            }
        }
        val started = probing(probe) { SystemStarter.start(unspawnable(Stdin.Bytes(byteArrayOf(1)))) }
        startAnswered.countDown()
        awaitThrower()

        started.assertError<ExecError.StartError>()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while waiting for the child closes its pidfd and reports a second throw, carried as suppressed`() {
        val again = IllegalStateException("a bug in kern, again")
        var waits = 0
        val probe: (FfmStep) -> Unit = {
            if (it == FfmStep.Wait) throwOnThisThread(if (waits++ == 0) bug else again)
        }
        val before = openPipes()
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        awaitThrower()

        assertEquals(before, openPipes())
        assertSame(bug, assertFails { child.drain() })
        assertEquals(listOf<Throwable>(again), bug.suppressedExceptions)
        assertEquals(listOf<Throwable>(bug, again), uncaught)
    }

    @Test
    fun `the same throw again while waiting for the child is reported once and rethrown unchanged`() {
        val probe: (FfmStep) -> Unit = { if (it == FfmStep.Wait) throwOnThisThread(bug) }
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        awaitThrower()

        assertSame(bug, assertFails { child.drain() })
        assertEquals(emptyList(), bug.suppressedExceptions)
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw while writing the input reaches the uncaught handler when the reader thread cannot start`() {
        var threadStarts = 0
        val probe: (FfmStep) -> Unit = {
            if (it == FfmStep.WriteStdin) throwOnThisThread(bug)
            if (it == FfmStep.ThreadStart && threadStarts++ == 1) {
                awaitThrower()
                // How the JVM reports a thread the system would not start.
                throw OutOfMemoryError("unable to create native thread")
            }
        }
        val command = Command(listOf("/bin/cat"), stdin = Stdin.Bytes(byteArrayOf(1)))

        probing(probe) { SystemStarter.start(command) }.assertError<ExecError.StartFailed>()
        assertEquals(listOf<Throwable>(bug), uncaught)
    }

    @Test
    fun `a throw at the first two waits for the child still reaps it and shuts stop off`() {
        val steps = CopyOnWriteArrayList<FfmStep>()
        var waits = 0
        val probe: (FfmStep) -> Unit = {
            steps += it
            if (it == FfmStep.Wait && waits++ < 2) throwOnThisThread(bug)
        }
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        awaitThrower()

        assertFalse(File("/proc/${child.pid}").exists(), "the child was not reaped")
        child.stop(Signal.Kill).assertSuccess()
        assertFalse(FfmStep.Stop in steps, "stop still signals the child's pid")
    }

    @Test
    fun `a throw right after the start and at the first two waits for the child still reaps it`() {
        val steps = CopyOnWriteArrayList<FfmStep>()
        var waits = 0
        val probe: (FfmStep) -> Unit = {
            steps += it
            if (it == FfmStep.Started || it == FfmStep.Wait && waits++ < 2) throwOnThisThread(bug)
        }
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        awaitThrower()

        assertFalse(File("/proc/${child.pid}").exists(), "the child was not reaped")
        child.stop(Signal.Kill).assertSuccess()
        assertFalse(FfmStep.Stop in steps, "stop still signals the child's pid")
    }

    @Test
    fun `a throw after the child was reaped closes no descriptor twice`() {
        val opened = CopyOnWriteArrayList<RandomAccessFile>()
        val throwing = throwingOnceAt(FfmStep.Reaped)
        val probe: (FfmStep) -> Unit = {
            // Opened once the child's pipes closed, so they likely get the numbers those pipes had.
            if (it == FfmStep.Reaped) repeat(2) { opened += RandomAccessFile("/dev/null", "r") }
            throwing(it)
        }
        val child = probing(probe) { SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess() }
        runCatching { child.drain() }
        awaitThrower()

        try {
            assertEquals(listOf(0L, 0L), opened.map { it.length() })
        } finally {
            opened.forEach { runCatching { it.close() } }
        }
    }

    @Test
    fun `a throw while stopping the child is rethrown unchanged from stop`() {
        val child = probing(throwingOnceAt(FfmStep.Stop)) { SystemStarter.start(sh("sleep 10")).assertSuccess() }

        try {
            assertSame(bug, assertFails { child.stop(Signal.Kill) })
        } finally {
            child.stop(Signal.Kill)
            child.drain()
        }
    }

    @Test
    fun `a throw while detaching the child is rethrown unchanged from detach`() {
        val child = probing(throwingOnceAt(FfmStep.Wake)) { SystemStarter.start(sh("sleep 0.1")).assertSuccess() }

        assertSame(bug, assertFails { child.detach() })
    }

    @Test
    fun `a thread the system cannot start is a failed start`() {
        val events = Command(
            listOf(
                ProcessHandle.current().info().command().get(),
                "--enable-native-access=ALL-UNNAMED",
                // The JVM's own warning about the thread it could not start would go to stdout.
                "-Xlog:os+thread=off",
                "-cp",
                System.getProperty("java.class.path"),
                StartWithoutThreads::class.java.name,
            ),
        ).events()
        val printed = events.text(ChildStream.Stdout)

        assumeFalse(printed == "${StartWithoutThreads.UNLIMITED}\n", "this user starts threads past the limit, as root")
        assertEquals("StartFailed\n", printed, events.text(ChildStream.Stderr))
    }
}

/** Runs [start] with [probe] as the [testProbe] of the children it starts. */
private fun <T> probing(probe: (FfmStep) -> Unit, start: () -> T): T {
    testProbe.set(probe)
    try {
        return start()
    } finally {
        testProbe.set(null)
    }
}

/** This process's open pipes, eventfds and pidfds, as `/proc/self/fd` names them. */
private fun openPipes(): Set<String> = File("/proc/self/fd")
    .listFiles()
    .orEmpty()
    .mapNotNull { runCatching { java.nio.file.Files.readSymbolicLink(it.toPath()).toString() }.getOrNull() }
    .filter { it.startsWith("pipe:") || it == "anon_inode:[eventfd]" || it == "anon_inode:[pidfd]" }
    .toSet()

/** A command whose program is found but cannot be started: its script names an interpreter that is not there. */
private fun unspawnable(stdin: Stdin): Command {
    val script = File.createTempFile("kern-unspawnable", ".sh").apply {
        deleteOnExit()
        writeText("#!/nonexistent/interpreter\n")
        setExecutable(true)
    }
    return Command(listOf(script.path), stdin = stdin)
}

/**
 * Lets this JVM start no more threads, then prints which error a start answers, or [UNLIMITED] when a thread still
 * starts: the kernel does not hold root to the limit.
 */
object StartWithoutThreads {
    private const val RLIMIT_NPROC = 6
    const val UNLIMITED = "threads start past the limit"

    @JvmStatic
    fun main(args: Array<String>) {
        val linker = Linker.nativeLinker()
        val setrlimit = linker.downcallHandle(
            linker.defaultLookup().find("setrlimit").orElseThrow(),
            FunctionDescriptor.of(JAVA_INT, JAVA_INT, ADDRESS),
        )
        // Load everything a start needs while threads can still start.
        SystemStarter.start(Command(listOf("/bin/true"))).assertSuccess().drain()
        Arena.ofConfined().use { arena ->
            val limit = arena.allocate(16)
            limit.set(JAVA_LONG, 0, 0L)
            limit.set(JAVA_LONG, 8, 0L)
            check(setrlimit.invokeWithArguments(RLIMIT_NPROC, limit) as Int == 0)
        }
        try {
            Thread {}.apply { start() }.join()
            println(UNLIMITED)
            return
        } catch (_: OutOfMemoryError) {
            // The JVM's answer to a thread the system would not start, as the limit means it to.
        }
        println(SystemStarter.start(Command(listOf("/bin/true"))).assertError<ExecError.StartError>()::class.simpleName)
    }
}
