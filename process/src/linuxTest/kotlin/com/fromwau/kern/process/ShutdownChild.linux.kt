@file:OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class, ExperimentalNativeApi::class)

package com.fromwau.kern.process

import com.fromwau.kern.result.errorOrNull
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.isSuccess
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import platform.posix.SIGHUP
import platform.posix.SIGINT
import platform.posix.SIGTERM
import platform.posix.SIG_BLOCK
import platform.posix.atexit
import platform.posix.pause
import platform.posix.sleep
import platform.posix.usleep
import platform.posix.pthread_self
import platform.posix.pthread_sigmask
import platform.posix.sigismember
import platform.posix.sigset_t
import platform.posix.write

private val mainThread = pthread_self()
private val handlerStarted = AtomicBoolean(false)
private val mainExiting = AtomicBoolean(false)

/** The program [ShutdownLinuxTest] signals: installs a handler as `args[0]` says, prints `ready`, then waits. */
fun shutdownChildMain(args: Array<String>) {
    when (args.single()) {
        "handle" -> install { request -> say("handled $request on ${threadKind(request)}") }
        // Long enough for a second request to end the process first, short enough to print when it does not.
        "block" -> install { request ->
            say("handling $request")
            sleep(5u)
            say("rest of the handler")
        }
        "throw" -> install { error("the handler failed") }
        "throw exit 0" -> install(exitCode = 0) { error("the handler failed") }
        "unprintable" -> install { throw Unprintable() }
        // The handler throws an exception that cannot be printed once main's exit is waiting for the handler.
        "unprintable during the exit of main" -> {
            handlerOutlastingMain { throw Unprintable() }
            exitProcess(3)
        }
        "main throws" -> mainThrowsWhileTheHandlerRuns(exitCode = null)
        "main throws exit 0" -> mainThrowsWhileTheHandlerRuns(exitCode = 0)
        "app hook, main throws exit 0" -> {
            setUnhandledExceptionHook { say("app hook saw ${it.message}") }
            mainThrowsWhileTheHandlerRuns(exitCode = 0)
        }
        "main throws with no request" -> {
            install {}
            error("main failed")
        }
        "app hook" -> {
            setUnhandledExceptionHook { say("app hook saw ${it.message}") }
            install {}
            error("main failed")
        }
        "exit3" -> install { exitProcess(3) }
        "handle exit 0" -> install(exitCode = 0) { request -> say("handled $request") }
        "block exit 0" -> install(exitCode = 0) { request ->
            say("handling $request")
            pause()
        }
        // Main exits while the handler runs, and the handler finishes only once that exit has started.
        "main exits 3" -> {
            handlerOutlastingMain(exitCode = 5) { say("handler finished") }
            say("main exits 3")
            exitProcess(3)
        }
        "stop" -> {
            stopLoop(exitCode = null)
            return
        }
        "stop exit 0" -> {
            stopLoop(exitCode = 0)
            return
        }
        "stop then exit 7" -> {
            stopLoop(exitCode = null)
            exitProcess(7)
        }
        "stop, atexit" -> {
            val stop = installStop(exitCode = null)
            check(atexit(staticCFunction(::sayAtExit)) == 0) { "atexit failed" }
            say("ready")
            while (!stop.requested) usleep(10_000u)
            return
        }
        // Long enough for a second request to end the process first, short enough to print when it does not.
        "stop, slow job" -> {
            awaitStopRequest(exitCode = null)
            say("stop requested")
            sleep(5u)
            say("rest of the job")
            return
        }
        "stop, main throws" -> {
            awaitStopRequest(exitCode = null)
            error("main failed")
        }
        "stop then handler" -> {
            say("${stopOnShutdownRequest().errorOrNull()}")
            say("${onShutdownRequest {}.errorOrNull()}")
            return
        }
        "handler then stop" -> {
            say("${onShutdownRequest {}.errorOrNull()}")
            say("${systemShutdownSource().stopOnShutdownRequest().errorOrNull()}")
            return
        }
        "twice" -> {
            say("${onShutdownRequest {}.errorOrNull()}")
            say("${systemShutdownSource().onShutdownRequest {}.errorOrNull()}")
            return
        }
        "sleep" -> {
            install { request -> say("handled $request") }
            say("ready")
            sleep(60u)
            say("main returned")
            return
        }
        // Main returns while the handler runs, and the handler finishes only once main has started exiting.
        "main returns" -> {
            handlerOutlastingMain { say("handler finished") }
            say("main returned")
            return
        }
    }
    say("ready")
    while (true) pause()
}

private fun mainThrowsWhileTheHandlerRuns(exitCode: Int?): Nothing {
    handlerOutlastingMain(exitCode) { say("handler finished") }
    say("main throws")
    error("main failed")
}

/** Installs a handler that runs [finish] only once main has started exiting, and returns once a request started it. */
private fun handlerOutlastingMain(
    exitCode: Int? = null,
    finish: () -> Unit,
) {
    install(exitCode) {
        handlerStarted.store(true)
        while (!mainExiting.load()) {
            // Spins until the atexit hook below has run.
        }
        finish()
    }
    check(atexit(staticCFunction(::markMainExiting)) == 0) { "atexit failed" }
    say("ready")
    while (!handlerStarted.load()) {
        // Spins until the request has started the handler.
    }
}

private class Unprintable : Exception() {
    override fun toString(): String = throw IllegalStateException("cannot print")
}

private fun installStop(exitCode: Int?): ShutdownStop {
    val installed = stopOnShutdownRequest(exitCode)
    return checkNotNull(installed.getOrNull()) { "not installed: $installed" }
}

/** Installs a stop, says `ready`, and polls it until a request arrives. */
@IgnorableReturnValue
private fun awaitStopRequest(exitCode: Int?): ShutdownStop {
    val stop = installStop(exitCode)
    say("ready")
    while (!stop.requested) usleep(10_000u)
    return stop
}

/** Polls the stop until a request arrives, then finishes its job. */
private fun stopLoop(exitCode: Int?) {
    val stop = awaitStopRequest(exitCode)
    say("job finished after ${stop.request}")
}

private fun sayAtExit() {
    say("atexit ran")
}

private fun markMainExiting() {
    mainExiting.store(true)
}

private fun install(
    exitCode: Int? = null,
    handler: (ShutdownRequest) -> Unit,
) {
    val installed = onShutdownRequest(exitCode, handler)
    check(installed.isSuccess()) { "not installed: $installed" }
}

/** `main` or `other` thread, and whether [request]'s signal is blocked here, as it is inside its own handler. */
private fun threadKind(request: ShutdownRequest): String = memScoped {
    val mask = alloc<sigset_t>()
    pthread_sigmask(SIG_BLOCK, null, mask.ptr)
    val signal = when (request) {
        ShutdownRequest.Interrupt -> SIGINT
        ShutdownRequest.Terminate -> SIGTERM
        ShutdownRequest.Hangup -> SIGHUP
    }
    val thread = if (pthread_self() == mainThread) "main" else "other"
    val blocked = if (sigismember(mask.ptr, signal) == 1) "blocked" else "unblocked"
    "$thread thread, signal $blocked"
}

// Unbuffered, so the parent sees each line as soon as it is said.
private fun say(line: String) {
    val bytes = "$line\n".encodeToByteArray()
    bytes.usePinned { pinned ->
        check(write(1, pinned.addressOf(0), bytes.size.convert()) == bytes.size.toLong()) { "cannot write \"$line\"" }
    }
}
