@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** A request from outside for the process to stop, and the exit code the process ends with after handling it. */
public enum class ShutdownRequest(public val exitCode: Int) {
    /** Ctrl-C: SIGINT, or a console's Ctrl-C on Windows. */
    Interrupt(130),

    /** SIGTERM, as sent by `kill`, a service manager or a container runtime. */
    Terminate(143),

    /** SIGHUP, as when the terminal closes, or a console window closing on Windows. */
    Hangup(129),
}

/** Why [onShutdownRequest] or [stopOnShutdownRequest] installed nothing. */
public sealed interface ShutdownError : ResultError {
    /** A handler or a stop is already installed in this process. */
    public data object AlreadyInstalled : ShutdownError

    /** No shutdown requests reach a program on this platform: a browser, WASI, or an Android app. */
    public data object Unsupported : ShutdownError

    /** The JVM keeps [requests] for itself, as it does for all three when started with `-Xrs`. */
    public data class Reserved(val requests: Set<ShutdownRequest>) : ShutdownError

    /** The system ran out of file descriptors, threads or memory. */
    public data object OutOfResources : ShutdownError

    /** The system refused for another reason. [code] is its `errno`, or `GetLastError` on Windows. */
    public data class System(val code: Int) : ShutdownError
}

private val installation = Installation(::installShutdownHandler, ::installShutdownStop)

/**
 * Installs [handler] for this process's shutdown requests: Ctrl-C, SIGTERM and SIGHUP.
 *
 * ```kotlin
 * fun main() {
 *     val server = startServer()
 *     onShutdownRequest { server.stop() }
 *         .onError { println("shutdown requests are not handled: $it") }
 *     server.awaitTermination()
 * }
 * ```
 *
 * A request that was ignored when the program started stays ignored, so a program started with `nohup` keeps
 * running when its terminal closes. Node cannot see this, and Windows does not check it yet; there all requests are
 * handled. A second request of any kind, while [handler] runs or the process is exiting, ends the process at once
 * with that request's [ShutdownRequest.exitCode].
 *
 * @param exitCode the code the process exits with when [handler] returns, in place of the request's
 *   [ShutdownRequest.exitCode]. Pass 0 for a service manager that counts 143 as a failure. A handler that throws, and
 *   a second request, still exit with the request's own code.
 * @param handler runs once, for the first request, on a thread of its own (on Node, on its event loop) rather than
 *   inside a signal handler. When it returns, the process exits with [exitCode], or the request's code without one,
 *   running shutdown hooks on the JVM and `atexit` handlers natively. When it throws, the exception's stack trace
 *   goes to standard error and the process exits with the request's code. If `main` returns, throws or exits
 *   meanwhile, the process still waits for [handler] and exits with its code, on the JVM, Linux and androidNative.
 *   On Linux and androidNative, set your own `setUnhandledExceptionHook` before this call: kern passes every uncaught
 *   exception on to it, also during shutdown, and then lets [handler] finish. A hook set after this call replaces
 *   kern's, and `main` throwing then ends the process at once. Do not call `exitProcess` in [handler]: natively that
 *   hangs when `main` has returned or is exiting, and on the JVM, when `main` is exiting at the same time, that call
 *   leaves `main`'s code.
 * @return success once installed, or why nothing was installed. Only a successful call makes later calls answer
 *   [ShutdownError.AlreadyInstalled].
 * @throws IllegalArgumentException when [exitCode] is outside 0..255, before anything is installed.
 * @throws IllegalStateException on the JVM when it is already shutting down, before anything is installed.
 */
public fun onShutdownRequest(
    exitCode: Int? = null,
    handler: (ShutdownRequest) -> Unit,
): EmptyResult<ShutdownError> = installation.install(exitCode, handler)

/**
 * Records the first shutdown request instead of running a handler: your code polls [ShutdownStop.requested] and
 * stops on its own.
 *
 * ```kotlin
 * fun main() {
 *     val stop = stopOnShutdownRequest().getOrNull()
 *     while (stop?.requested != true) processNextJob()
 * }
 * ```
 *
 * Under Node requests arrive on the event loop, so a loop that never yields never sees one: poll from asynchronous
 * code there.
 *
 * The process then ends when `main` returns or anything exits, crashes included, with [exitCode], or the first
 * request's [ShutdownRequest.exitCode] without one. Before a request it ends as it would without this call. A second
 * request ends the process at once with its own code. Requests ignored at startup stay ignored, as for
 * [onShutdownRequest].
 *
 * @param exitCode the code the process exits with after a request, in place of the request's own. Leave it null when
 *   a crash during cleanup must not look like success.
 * @return the [ShutdownStop] to poll, or why nothing was installed. It shares [onShutdownRequest]'s one install per
 *   process: after either succeeded, both answer [ShutdownError.AlreadyInstalled].
 * @throws IllegalArgumentException when [exitCode] is outside 0..255, before anything is installed.
 * @throws IllegalStateException on the JVM when it is already shutting down, before anything is installed.
 */
public fun stopOnShutdownRequest(exitCode: Int? = null): Result<ShutdownStop, ShutdownError> =
    installation.stop(exitCode)

/** What [stopOnShutdownRequest] recorded: whether a shutdown request has arrived, and which. */
public interface ShutdownStop {
    /** Whether a shutdown request has arrived. */
    public val requested: Boolean

    /** The first request, or null before one arrives. */
    public val request: ShutdownRequest?
}

/**
 * Where shutdown requests come from: [systemShutdownSource] in a program, and `FakeShutdownSource` from
 * `kern:process-test` in a test. Take one in code that installs a handler or a stop, so a test can send requests to
 * it.
 */
public interface ShutdownSource {
    /** Installs [handler] as the top-level [com.fromwau.kern.process.onShutdownRequest] does. */
    public fun onShutdownRequest(
        exitCode: Int? = null,
        handler: (ShutdownRequest) -> Unit,
    ): EmptyResult<ShutdownError>

    /** Records the first request as the top-level [com.fromwau.kern.process.stopOnShutdownRequest] does. */
    public fun stopOnShutdownRequest(exitCode: Int? = null): Result<ShutdownStop, ShutdownError>
}

/** The process's own shutdown requests, the ones the top-level [onShutdownRequest] and [stopOnShutdownRequest] use. */
public fun systemShutdownSource(): ShutdownSource = systemSource

private val systemSource: ShutdownSource = object : ShutdownSource {
    override fun onShutdownRequest(
        exitCode: Int?,
        handler: (ShutdownRequest) -> Unit,
    ): EmptyResult<ShutdownError> = com.fromwau.kern.process.onShutdownRequest(exitCode, handler)

    override fun stopOnShutdownRequest(exitCode: Int?): Result<ShutdownStop, ShutdownError> =
        com.fromwau.kern.process.stopOnShutdownRequest(exitCode)
}

/**
 * Installs one handler or stop per process through [installHandler] or [installStop]; [onWait] runs while another
 * call is installing.
 */
internal class Installation(
    private val installHandler: (onRequest: (ShutdownRequest) -> Int) -> EmptyResult<ShutdownError>,
    private val installStop: (exitCode: Int?) -> Result<ShutdownStop, ShutdownError>,
    private val onWait: () -> Unit = {},
) {
    private enum class State {
        Idle,
        Installing,
        Installed,
    }

    private val state = AtomicReference(State.Idle)

    fun install(
        exitCode: Int?,
        handler: (ShutdownRequest) -> Unit,
    ): EmptyResult<ShutdownError> = guarded(exitCode) {
        installHandler { request ->
            val returned = runHandler(handler, request)
            if (returned) exitCode ?: request.exitCode else request.exitCode
        }
    }

    fun stop(exitCode: Int?): Result<ShutdownStop, ShutdownError> = guarded(exitCode) { installStop(exitCode) }

    private fun <T> guarded(
        exitCode: Int?,
        installPlatform: () -> Result<T, ShutdownError>,
    ): Result<T, ShutdownError> {
        require(exitCode == null || exitCode in 0..255) { "exitCode must be in 0..255, was $exitCode" }
        // An install under way may still fail, which would make AlreadyInstalled a lie, so wait for its outcome.
        while (!state.compareAndSet(expectedValue = State.Idle, newValue = State.Installing)) {
            if (state.load() == State.Installed) return Err(ShutdownError.AlreadyInstalled)
            onWait()
        }
        var result: Result<T, ShutdownError>? = null
        try {
            result = installPlatform()
            return result
        } finally {
            state.store(if (result is Result.Success) State.Installed else State.Idle)
        }
    }
}

/** Runs [handler], printing what it throws; true when it returned. */
private fun runHandler(
    handler: (ShutdownRequest) -> Unit,
    request: ShutdownRequest,
): Boolean = try {
    handler(request)
    true
} catch (e: Throwable) {
    printUncaught(e)
    false
}

/**
 * Installs every request handler or none: [onRequest] runs for the first request, and the platform then exits with
 * the code it returns.
 */
internal expect fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError>

/** Installs every request handler or none, recording the first request and exiting with [exitCode] or its code. */
internal expect fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError>

/** Prints [e] to standard error the way this platform prints an uncaught exception. */
internal expect fun printUncaught(e: Throwable)
