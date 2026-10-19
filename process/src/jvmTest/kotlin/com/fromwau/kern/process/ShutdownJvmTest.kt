package com.fromwau.kern.process

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ShutdownJvmTest {
    @Test
    fun `SIGINT runs the handler once on a thread of its own, then exits 130`() {
        val child = Child.start("handle")
        child.signal("INT")

        val exit = child.finish()
        assertEquals(130, exit.code, exit.toString())
        val handled = exit.lines.filter { it.startsWith("handled") }
        assertEquals(1, handled.size, exit.toString())
        val thread = handled.single().substringAfter(" on ")
        assertTrue(handled.single().startsWith("handled Interrupt "), exit.toString())
        assertTrue(thread != "main" && thread != "Signal Dispatcher" && !thread.startsWith("SIG"), thread)
    }

    @Test
    fun `SIGTERM exits 143`() {
        val child = Child.start("handle")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue("handled Terminate" in exit.lines.single { it.startsWith("handled") }, exit.toString())
    }

    @Test
    fun `SIGHUP exits 129`() {
        val child = Child.start("handle")
        child.signal("HUP")

        val exit = child.finish()
        assertEquals(129, exit.code, exit.toString())
        assertTrue("handled Hangup" in exit.lines.single { it.startsWith("handled") }, exit.toString())
    }

    @Test
    fun `a second request while the handler runs exits at once with its own code`() {
        val child = Child.start("block")
        child.signal("TERM")
        child.awaitLine("handling Terminate")
        child.signal("HUP")

        val exit = child.finish()
        assertTrue("rest of the handler" !in exit.lines, exit.toString())
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun `a handler that throws prints its stack trace and still exits 143`() {
        val child = Child.start("throw")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertTrue("java.lang.IllegalStateException: the handler failed" in exit.stderr, exit.toString())
        assertTrue("\tat com.fromwau.kern.process.ShutdownChild" in exit.stderr, exit.toString())
    }

    @Test
    fun `a handler that throws exits with the request's code even with an exitCode given at install`() {
        val child = Child.start("throw exit 0")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `main exiting the moment the handler returns still exits with the handler's code`() {
        val child = Child.start("main exits as the handler returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
    }

    @Test
    fun `a hangup ignored at start stays ignored while a terminate is handled`() {
        val child = Child.start("handle", hangupIgnored = true)
        child.signal("HUP")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(
            listOf("handled Terminate"),
            exit.lines.filter { it.startsWith("handled") }.map { it.substringBefore(" on ") },
            exit.toString(),
        )
    }

    @Test
    fun `main returning while the handler runs still exits 143 once the handler is done`() {
        val child = Child.start("main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main returned", "handler finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `the exit after the handler lets a slow app shutdown hook finish`() {
        val child = Child.start("slow app hook")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("handled Terminate", "app hook finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `an exitCode given at install replaces the request's code`() {
        val child = Child.start("handle exit 0")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertTrue("handled Terminate" in exit.lines, exit.toString())
    }

    @Test
    fun `a second request exits with its own code even with an exitCode given at install`() {
        val child = Child.start("block exit 0")
        child.signal("TERM")
        child.awaitLine("handling Terminate")
        child.signal("HUP")

        val exit = child.finish()
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun `main exiting with its own code while the handler runs exits with the handler's code once it is done`() {
        val child = Child.start("main exits 3")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(5, exit.code, exit.toString())
        assertEquals(listOf("main exits 3", "handler finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `a handler that calls exitProcess while main is exiting leaves main's code`() {
        val child = Child.start("handler exits during main's exit")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(3, exit.code, exit.toString())
    }

    @Test
    fun `a handler whose exception cannot be printed still ends a waiting exit with the request's code`() {
        val child = Child.start("unprintable during main's exit")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `a handler that calls exitProcess picks the exit code`() {
        val child = Child.start("exit3")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(3, exit.code, exit.toString())
    }

    @Test
    fun `a stop lets the loop finish its job, then main returning exits 143`() {
        val child = Child.start("stop")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `a stop with an exitCode exits with it`() {
        val child = Child.start("stop exit 0")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `an exitProcess after a stop still exits with the request's code`() {
        val child = Child.start("stop then exit 7")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `main returning after a stop lets a slow app shutdown hook finish`() {
        val child = Child.start("stop, slow app hook")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate", "app hook finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `an exitProcess after a stop lets a slow app shutdown hook finish`() {
        val child = Child.start("stop, slow app hook, exit 7")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("job finished after Terminate", "app hook finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `an exitProcess after a stop does not wait for a worker started before the request`() {
        val child = Child.start("stop, endless worker, exit 7")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `a stop request after main returned ends the process although a worker still runs`() {
        val child = Child.start("stop, endless worker, main returned")
        child.awaitLine("main returned")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `a stop request after main returned exits with the exitCode given`() {
        val child = Child.start("stop exit 0, endless worker, main returned")
        child.awaitLine("main returned")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
    }

    @Test
    fun `main returning after a stop request ends the process although a worker still runs`() {
        val child = Child.start("stop, endless worker, main returns after the request")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main returns"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `an exitProcess from a thread started after the request, once main returned, still exits 143`() {
        val child = Child.start("stop, late exit 9, main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `an exitProcess from a thread started after the request, while main runs, exits 143`() {
        val child = Child.start("stop, late exit 9, main runs")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `main returning after a stop cuts off a worker started after the request`() {
        val child = Child.start("stop, endless worker after the request, main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
    }

    @Test
    fun `main returning after a stop cuts off a later worker and exits with the exitCode given`() {
        val child = Child.start("stop exit 0, endless worker after the request, main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(0, exit.code, exit.toString())
    }

    @Test
    fun `main returning after a stop with a later worker still lets a slow daemon app hook finish`() {
        val child = Child.start("stop, slow daemon app hook, endless worker after the request, main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main returns", "daemon hook finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `main returning after a stop with a later worker still lets a slow app hook finish`() {
        val child = Child.start("stop, slow app hook, endless worker after the request, main returns")
        child.signal("TERM")

        val exit = child.finish()
        assertEquals(143, exit.code, exit.toString())
        assertEquals(listOf("main returns", "app hook finished"), exit.lines.drop(1), exit.toString())
    }

    @Test
    fun `a second request after a stop exits at once with its own code`() {
        val child = Child.start("stop, slow job")
        child.signal("TERM")
        child.awaitLine("stop requested")
        child.signal("HUP")

        val exit = child.finish()
        assertTrue("rest of the job" !in exit.lines, exit.toString())
        assertEquals(129, exit.code, exit.toString())
    }

    @Test
    fun `a stop and a handler share the one install`() {
        val stopFirst = Child.start("stop then handler", awaitReady = false).finish()
        assertEquals(listOf("null", "AlreadyInstalled"), stopFirst.lines, stopFirst.toString())

        val handlerFirst = Child.start("handler then stop", awaitReady = false).finish()
        assertEquals(listOf("null", "AlreadyInstalled"), handlerFirst.lines, handlerFirst.toString())
    }

    @Test
    fun `a second install is AlreadyInstalled`() {
        val exit = Child.start("twice", awaitReady = false).finish()

        assertEquals(listOf("null", "AlreadyInstalled"), exit.lines, exit.toString())
    }

    @Test
    fun `a Reserved install leaves no shutdown hook behind`() {
        val exit = Child.start("reserved, hook", jvmOptions = listOf("-Xrs"), awaitReady = false).finish()

        assertEquals(listOf("Reserved(requests=[Interrupt, Terminate, Hangup])", "no hook"), exit.lines, exit.toString())
    }

    @Test
    fun `installing while the JVM shuts down throws and replaces no signal handler`() {
        val exit = Child.start("install during exit", awaitReady = false).finish()

        assertEquals(listOf("IllegalStateException", "TERM untouched"), exit.lines, exit.toString())
    }

    @Test
    fun `under -Xrs the JVM keeps every request for itself`() {
        val exit = Child.start("reserved", jvmOptions = listOf("-Xrs"), awaitReady = false).finish()

        assertEquals(listOf("Reserved(requests=[Interrupt, Terminate, Hangup])"), exit.lines, exit.toString())
    }
}

private class Child(private val process: Process, private val stderrFile: File) {
    private val stdout = process.inputStream.bufferedReader()
    private val seen = mutableListOf<String>()

    fun awaitLine(expected: String) {
        while (true) {
            val line = stdout.readLine()
                ?: fail("the child ended before printing \"$expected\": $seen\n${stderrFile.readText()}")
            seen += line
            if (line == expected) return
        }
    }

    fun signal(name: String) {
        val kill = ProcessBuilder("kill", "-s", name, process.pid().toString()).inheritIO().start()
        check(kill.waitFor() == 0) { "kill -s $name failed" }
    }

    fun finish(): Exit {
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly().waitFor()
            fail("the child did not exit: $seen\n${stderrFile.readText()}")
        }
        val exit = Exit(process.exitValue(), seen + stdout.readLines(), stderrFile.readText())
        stderrFile.delete()
        return exit
    }

    data class Exit(val code: Int, val lines: List<String>, val stderr: String)

    companion object {
        fun start(
            mode: String,
            jvmOptions: List<String> = emptyList(),
            hangupIgnored: Boolean = false,
            awaitReady: Boolean = true,
        ): Child {
            val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath
            val command = listOf(java) + jvmOptions +
                listOf("-cp", System.getProperty("java.class.path"), ShutdownChild::class.java.name, mode)
            // An ignored signal stays ignored across exec, which is all nohup does to SIGHUP.
            val launch = if (hangupIgnored) listOf("sh", "-c", "trap '' HUP; exec \"$@\"", "sh") + command else command
            val stderrFile = File.createTempFile("kern-process-child", ".err").apply { deleteOnExit() }
            val process = ProcessBuilder(launch).redirectError(stderrFile).start()
            return Child(process, stderrFile).also { if (awaitReady) it.awaitLine("ready") }
        }
    }
}
