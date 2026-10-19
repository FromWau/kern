@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.process

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.toKString
import platform.posix.FILE
import platform.posix.POLLIN
import platform.posix.SIGABRT
import platform.posix.SIGHUP
import platform.posix.SIGINT
import platform.posix.SIGKILL
import platform.posix.SIGTERM
import platform.posix.fclose
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fread
import platform.posix.getenv
import platform.posix.getpid
import platform.posix.kill
import platform.posix.pclose
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.popen
import platform.posix.read
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ShutdownLinuxTest {
    @Test
    fun sigintRunsTheHandlerOnceOnAnOrdinaryThreadThenExits130() {
        val child = NativeChild.start("handle")
        child.signal(SIGINT)

        val exit = child.finish()
        assertEquals(130, exit.code, exit.toString())
        assertEquals(
            listOf("handled Interrupt on other thread, signal unblocked"),
            exit.lines.filter { it.startsWith("handled") },
            exit.toString(),
        )
    }

    @Test
    fun sigtermExits143() {
        val child = NativeChild.start("handle")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue(exit.lines.single { it.startsWith("handled") }.startsWith("handled Terminate "), exit.toString())
    }

    @Test
    fun sighupExits129() {
        val child = NativeChild.start("handle")
        child.signal(SIGHUP)

        val exit = child.finish()
        assertEquals(129, exit.code, exit.toString())
        assertTrue(exit.lines.single { it.startsWith("handled") }.startsWith("handled Hangup "), exit.toString())
    }

    @Test
    fun aSecondRequestWhileTheHandlerRunsExitsAtOnceWithItsOwnCode() {
        val child = NativeChild.start("block")
        child.signal(SIGTERM)
        child.awaitLine("handling Terminate")
        child.signal(SIGHUP)

        val exit = child.finish()
        assertTrue("rest of the handler" !in exit.lines, exit.toString())
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun aHandlerThatThrowsPrintsItsStackTraceAndStillExits143() {
        val child = NativeChild.start("throw")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue("IllegalStateException: the handler failed" in exit.stderr, exit.toString())
        assertTrue("    at " in exit.stderr, exit.toString())
    }

    @Test
    fun aHandlerThatThrowsExitsWithTheRequestsCodeEvenWithAnExitCodeGivenAtInstall() {
        val child = NativeChild.start("throw exit 0")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun mainThrowingWhileTheHandlerRunsExits143OnceTheHandlerIsDone() {
        val child = NativeChild.start("main throws")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main throws", "handler finished"), exit.lines.drop(1), exit.toString())
        assertTrue("IllegalStateException: main failed" in exit.stderr, exit.toString())
    }

    @Test
    fun mainThrowingWhileTheHandlerRunsExitsWithAnExitCodeGivenAtInstall() {
        val child = NativeChild.start("main throws exit 0")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertEquals(listOf("main throws", "handler finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun anUnhandledExceptionHookSetBeforeInstallingRunsWhenMainThrowsWhileTheHandlerRuns() {
        val child = NativeChild.start("app hook, main throws exit 0")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertEquals(
            listOf("main throws", "app hook saw main failed", "handler finished"),
            exit.lines.drop(1),
            exit.toString(),
        )
    }

    @Test
    fun mainThrowingWithNoRequestAbortsAsKotlinDoesByDefault() {
        val exit = NativeChild.start("main throws with no request", awaitReady = false).finish()

        assertEquals(-SIGABRT, exit.code, exit.toString())
        assertTrue(exit.stderr.startsWith("kotlin.IllegalStateException: main failed\n    at "), exit.toString())
    }

    @Test
    fun anUnhandledExceptionHookSetBeforeInstallingStillRunsWithNoRequest() {
        val exit = NativeChild.start("app hook", awaitReady = false).finish()

        assertEquals(listOf("app hook saw main failed"), exit.lines, exit.toString())
        assertEquals(-SIGABRT, exit.code, exit.toString())
    }

    @Test
    fun aHangupIgnoredAtStartStaysIgnoredWhileATerminateIsHandled() {
        val child = NativeChild.start("handle", hangupIgnored = true)
        child.signal(SIGHUP)
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(
            listOf("handled Terminate"),
            exit.lines.filter { it.startsWith("handled") }.map { it.substringBefore(" on ") },
            exit.toString(),
        )
    }

    @Test
    fun mainReturningWhileTheHandlerRunsStillExits143OnceTheHandlerIsDone() {
        val child = NativeChild.start("main returns")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main returned", "handler finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun aRequestThatWakesASleepingMainStillRunsTheHandlerAndExits143() {
        val child = NativeChild.start("sleep")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue("handled Terminate" in exit.lines, exit.toString())
    }

    @Test
    fun anExitCodeGivenAtInstallReplacesTheRequestsCode() {
        val child = NativeChild.start("handle exit 0")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertTrue("handled Terminate" in exit.lines, exit.toString())
    }

    @Test
    fun aSecondRequestExitsWithItsOwnCodeEvenWithAnExitCodeGivenAtInstall() {
        val child = NativeChild.start("block exit 0")
        child.signal(SIGTERM)
        child.awaitLine("handling Terminate")
        child.signal(SIGHUP)

        val exit = child.finish()
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun mainExitingWithItsOwnCodeWhileTheHandlerRunsExitsWithTheHandlersCodeOnceItIsDone() {
        val child = NativeChild.start("main exits 3")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(5, exit.code, exit.toString())
        assertEquals(listOf("main exits 3", "handler finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun aHandlerWhoseExceptionCannotBePrintedStillExitsWithTheRequestsCode() {
        val child = NativeChild.start("unprintable")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun aHandlerWhoseExceptionCannotBePrintedStillEndsAWaitingExitWithTheRequestsCode() {
        val child = NativeChild.start("unprintable during the exit of main")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun aHandlerThatCallsExitProcessPicksTheExitCode() {
        val child = NativeChild.start("exit3")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(3, exit.code, exit.toString())
    }

    @Test
    fun aStopLetsTheLoopFinishItsJobThenMainReturningExits143() {
        val child = NativeChild.start("stop")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun aStopWithAnExitCodeExitsWithIt() {
        val child = NativeChild.start("stop exit 0")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun anExitProcessAfterAStopStillExitsWithTheRequestsCode() {
        val child = NativeChild.start("stop then exit 7")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun anAtexitHandlerRegisteredAfterAStopRunsBeforeTheExit() {
        val child = NativeChild.start("stop, atexit")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("atexit ran"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun aSecondRequestAfterAStopExitsAtOnceWithItsOwnCode() {
        val child = NativeChild.start("stop, slow job")
        child.signal(SIGTERM)
        child.awaitLine("stop requested")
        child.signal(SIGHUP)

        val exit = child.finish()
        assertTrue("rest of the job" !in exit.lines, exit.toString())
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun mainThrowingAfterAStopPrintsTheExceptionAndExitsWithTheRequestsCode() {
        val child = NativeChild.start("stop, main throws")
        child.signal(SIGTERM)

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue("IllegalStateException: main failed" in exit.stderr, exit.toString())
    }

    @Test
    fun aStopAndAHandlerShareTheOneInstall() {
        val stopFirst = NativeChild.start("stop then handler", awaitReady = false).finish()
        assertEquals(listOf("null", "AlreadyInstalled"), stopFirst.lines, stopFirst.toString())

        val handlerFirst = NativeChild.start("handler then stop", awaitReady = false).finish()
        assertEquals(listOf("null", "AlreadyInstalled"), handlerFirst.lines, handlerFirst.toString())
    }

    @Test
    fun aSecondInstallIsAlreadyInstalled() {
        val exit = NativeChild.start("twice", awaitReady = false).finish()

        assertEquals(listOf("null", "AlreadyInstalled"), exit.lines, exit.toString())
    }
}

private const val WAIT_MILLIS = 30_000

private class NativeChild(
    private val shell: CPointer<FILE>,
    private val stderrPath: String,
) {
    private val stdout = fileno(shell)
    private val seen = mutableListOf<String>()
    private var pending = ""
    private var ended = false
    private val pid: Int

    init {
        while (seen.isEmpty() && !ended) readMore()
        pid = seen.removeFirstOrNull()?.toIntOrNull() ?: fail("the shell printed no pid\n${readStderr()}")
    }

    fun awaitLine(expected: String) {
        var checked = 0
        while (true) {
            while (checked < seen.size) {
                if (seen[checked++] == expected) return
            }
            if (ended) fail("the child ended before printing \"$expected\": $seen\n${readStderr()}")
            readMore()
        }
    }

    fun signal(signal: Int) {
        check(kill(pid, signal) == 0) { "kill $signal failed" }
    }

    fun finish(): Exit {
        while (!ended) readMore()
        val status = pclose(shell)
        val signalled = status and 0x7f
        val code = if (signalled == 0) (status shr 8) and 0xff else -signalled
        val exit = Exit(code, seen.toList(), readStderr())
        unlink(stderrPath)
        return exit
    }

    /** Reads what the child wrote next, killing it and failing when it writes nothing within [WAIT_MILLIS]. */
    private fun readMore() {
        val count = memScoped {
            val watched = alloc<pollfd>()
            watched.fd = stdout
            watched.events = POLLIN.convert()
            if (poll(watched.ptr, 1u, WAIT_MILLIS) == 0) {
                kill(pid, SIGKILL)
                fail("the child went quiet: $seen\n${readStderr()}")
            }
            val buffer = allocArray<ByteVar>(4096)
            val count = read(stdout, buffer, 4096u).toInt()
            if (count > 0) pending += buffer.readBytes(count).decodeToString()
            count
        }
        if (count <= 0) ended = true
        val lines = pending.split('\n')
        seen += lines.dropLast(1)
        pending = lines.last()
    }

    private fun readStderr(): String = readFile(stderrPath)

    data class Exit(val code: Int, val lines: List<String>, val stderr: String)

    companion object {
        private var counter = 0

        fun start(
            mode: String,
            hangupIgnored: Boolean = false,
            awaitReady: Boolean = true,
        ): NativeChild {
            val program = getenv("KERN_PROCESS_CHILD")?.toKString() ?: fail("KERN_PROCESS_CHILD is not set")
            val stderrPath = "/tmp/kern-process-child-${getpid()}-${counter++}.err"
            // The shell prints its pid and then becomes the child, keeping it. An ignored signal stays ignored
            // across exec, which is all nohup does to SIGHUP.
            val ignoreHangup = if (hangupIgnored) "trap '' HUP; " else ""
            val script = "exec 2>'$stderrPath'; ${ignoreHangup}echo \$\$; exec '$program' '$mode'"
            val shell = popen(script, "r") ?: fail("popen failed")
            return NativeChild(shell, stderrPath).also { if (awaitReady) it.awaitLine("ready") }
        }
    }
}

private fun readFile(path: String): String {
    val file = fopen(path, "r") ?: return ""
    val text = StringBuilder()
    memScoped {
        val buffer = allocArray<ByteVar>(4096)
        while (true) {
            val count = fread(buffer, 1u, 4096u, file).toInt()
            if (count <= 0) break
            text.append(buffer.readBytes(count).decodeToString())
        }
    }
    fclose(file)
    return text.toString()
}
