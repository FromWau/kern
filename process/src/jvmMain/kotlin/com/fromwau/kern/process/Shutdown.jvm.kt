package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.system.exitProcess
import sun.misc.Signal
import sun.misc.SignalHandler

private val requested = AtomicBoolean(false)
private val handlerThread = AtomicReference<Thread?>(null)
private val handlerDone = CountDownLatch(1)

@Volatile
private var handlerExitCode = 0

private val stopRequest = AtomicReference<ShutdownRequest?>(null)

@Volatile
private var stopExitCode: Int? = null

@Volatile
private var threadsAtRequest: Set<Thread> = emptySet()

@Volatile
private var threadsAtKernExit: Set<Thread> = emptySet()

private val kernExitThread = AtomicReference<Thread?>(null)

/** The shutdown hook the last install created, for tests to check it is registered only when the install succeeded. */
internal var lastShutdownHook: Thread? = null

private object SignalStop : ShutdownStop {
    override val requested: Boolean get() = stopRequest.get() != null
    override val request: ShutdownRequest? get() = stopRequest.get()
}

internal actual fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError> =
    installSignals(onSignal = { request -> onSignal(request, onRequest) }, hook = ::awaitHandler)

internal actual fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError> {
    stopExitCode = exitCode
    return installSignals(onSignal = ::onStopSignal, hook = ::endStopped).map { SignalStop }
}

/** Handles every request with [onSignal], leaving ignored ones ignored, and runs [hook] as a shutdown hook. */
private fun installSignals(
    onSignal: (ShutdownRequest) -> Unit,
    hook: () -> Unit,
): EmptyResult<ShutdownError> {
    // First, so that installing while the JVM shuts down throws before any handler is replaced.
    val hookThread = Thread(hook, "kern shutdown wait")
    lastShutdownHook = hookThread
    Runtime.getRuntime().addShutdownHook(hookThread)
    val replaced = mutableListOf<Pair<Signal, SignalHandler>>()
    val reserved = mutableSetOf<ShutdownRequest>()
    for (request in ShutdownRequest.entries) {
        // Windows has no SIGHUP, and the JVM there refuses to name it.
        val signal = try {
            Signal(request.signalName)
        } catch (_: IllegalArgumentException) {
            continue
        }
        val previous = try {
            Signal.handle(signal) { onSignal(request) }
        } catch (_: IllegalArgumentException) {
            reserved += request
            continue
        }
        if (previous == SignalHandler.SIG_IGN) {
            Signal.handle(signal, SignalHandler.SIG_IGN)
        } else {
            replaced += signal to previous
        }
    }
    if (reserved.isEmpty()) return Ok(Unit)
    replaced.forEach { (installed, original) -> Signal.handle(installed, original) }
    Runtime.getRuntime().removeShutdownHook(hookThread)
    return Err(ShutdownError.Reserved(reserved))
}

private fun onSignal(
    request: ShutdownRequest,
    onRequest: (ShutdownRequest) -> Int,
) {
    if (!requested.compareAndSet(false, true)) Runtime.getRuntime().halt(request.exitCode)
    val thread = Thread(
        {
            handlerExitCode = request.exitCode
            // Even when printing what the handler threw fails, the process must still end with the request's code.
            try {
                handlerExitCode = onRequest(request)
            } finally {
                handlerDone.countDown()
                exitProcess(handlerExitCode)
            }
        },
        "kern shutdown handler",
    )
    // When main returns, the JVM then waits for the handler before its exit begins, so app shutdown hooks start after
    // the handler rather than alongside it.
    thread.isDaemon = false
    handlerThread.set(thread)
    thread.start()
}

private fun onStopSignal(request: ShutdownRequest) {
    // Set before the request is visible, so the hook never sees a request without it.
    threadsAtRequest = Thread.getAllStackTraces().keys
    if (!stopRequest.compareAndSet(null, request)) Runtime.getRuntime().halt(request.exitCode)
    val exit = Thread({ exitOnceMainReturned(stopExitCodeFor(request)) }, "kern stop exit")
    exit.isDaemon = true
    kernExitThread.set(exit)
    exit.start()
}

/** Exits once `main` has returned, which a non-daemon thread left running would otherwise keep from ending. */
private fun exitOnceMainReturned(exitCode: Int) {
    // The launcher's DestroyJavaVM thread appears only once main has returned, to wait for the non-daemon threads.
    while (liveThreads().none { it.isDestroyJavaVm() }) Thread.sleep(50)
    // No hook runs before this exit starts, so every thread alive now is the app's own and is cut off.
    threadsAtKernExit = Thread.getAllStackTraces().keys
    exitProcess(exitCode)
}

/** Ends a stopped process with its code once the other shutdown hooks are done. */
private fun endStopped() {
    val request = stopRequest.get() ?: return
    val ending = Thread.getAllStackTraces().keys.firstOrNull { it.exitRole() == ExitRole.Owner }
    if (ending != null) {
        // The exit starts every hook before it joins the first one.
        while (ending.isAlive && !ending.isJoining()) Thread.sleep(1)
        // Hooks start during the exit, so threads running before it are cut off. For an exit another thread started,
        // the request is the latest point known to come before it, and daemon pools started since must not hang it.
        val kernExit = ending === kernExitThread.get()
        val earlier = if (kernExit) threadsAtKernExit else threadsAtRequest
        Thread.getAllStackTraces()
            .keys
            .filter { it !== ending && it !== Thread.currentThread() && it !in earlier && (kernExit || !it.isDaemon) }
            // DestroyJavaVM waits for every non-daemon thread, so it would never end while one keeps running.
            .filter { !it.isDestroyJavaVm() }
            .forEach { it.joinUnlessExiting() }
    }
    Runtime.getRuntime().halt(stopExitCodeFor(request))
}

private fun stopExitCodeFor(request: ShutdownRequest): Int = stopExitCode ?: request.exitCode

/** Waits for this thread to end, but not when it is blocked calling exit, as it would wait for this hook forever. */
private fun Thread.joinUnlessExiting() {
    while (isAlive && exitRole() != ExitRole.Blocked) join(50)
}

private fun Thread.isDestroyJavaVm(): Boolean = name == "DestroyJavaVM"

/** Every live thread, without the stack walk [Thread.getAllStackTraces] costs. */
private fun liveThreads(): List<Thread> {
    var root = Thread.currentThread().threadGroup
    while (root.parent != null) root = root.parent
    // enumerate fills at most the array's size, so grow it until the threads fit with room to spare.
    var threads = arrayOfNulls<Thread>(root.activeCount() + 8)
    while (root.enumerate(threads, true) == threads.size) threads = arrayOfNulls(threads.size * 2)
    return threads.filterNotNull()
}

private fun Thread.isJoining(): Boolean =
    stackTrace.any { it.className == "java.lang.Thread" && it.methodName == "join" }

/** Makes any other exit during a running handler wait for it and end with its code instead. */
private fun awaitHandler() {
    if (!requested.get()) return
    while (true) {
        when (handlerThread.get()?.exitRole()) {
            ExitRole.Owner -> return
            // Done: kern's own exit lost to another one. Not done: the handler's own exit lost, and the other one wins.
            ExitRole.Blocked -> if (handlerDone.count == 0L) Runtime.getRuntime().halt(handlerExitCode) else return
            null -> if (handlerDone.await(50, TimeUnit.MILLISECONDS)) Runtime.getRuntime().halt(handlerExitCode)
        }
    }
}

private enum class ExitRole {
    /** Started the exit that is running the shutdown hooks. */
    Owner,

    /** Called exit while another one runs, and waits for it. */
    Blocked,
}

private fun Thread.exitRole(): ExitRole? {
    val shutdownFrames = stackTrace.filter { it.className == "java.lang.Shutdown" }
    return when {
        shutdownFrames.isEmpty() -> null
        shutdownFrames.any { it.methodName == "runHooks" } -> ExitRole.Owner
        else -> ExitRole.Blocked
    }
}

private val ShutdownRequest.signalName: String
    get() = when (this) {
        ShutdownRequest.Interrupt -> "INT"
        ShutdownRequest.Terminate -> "TERM"
        ShutdownRequest.Hangup -> "HUP"
    }
