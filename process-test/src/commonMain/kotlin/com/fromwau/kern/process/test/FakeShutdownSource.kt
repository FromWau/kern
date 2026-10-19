@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.process.test

import com.fromwau.kern.process.ShutdownError
import com.fromwau.kern.process.ShutdownRequest
import com.fromwau.kern.process.ShutdownSource
import com.fromwau.kern.process.ShutdownStop
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Shutdown requests a test sends itself. Nothing exits: [request] runs the installed handler on the calling thread,
 * or sets the installed stop, and records the exit code the process would have ended with.
 *
 * ```kotlin
 * val source = FakeShutdownSource()
 * val server = Server(source)        // installs its handler through source.onShutdownRequest
 * source.request(ShutdownRequest.Terminate)
 * assertTrue(server.stopped)
 * assertEquals(143, source.exitCode)
 * ```
 *
 * Install before sending requests: a request with no handler installed counts as the process ending, so a handler
 * installed after it never runs. The handler runs synchronously on the thread calling [request], so a handler that
 * waits for something that thread must do deadlocks; a stop cannot. The fake cannot end a handler early: after a
 * second request the rest of the handler still runs, and only [exitCode] shows that a real process would have ended
 * there. A handler that calls `exitProcess` still ends the process, which in a test is the test run itself.
 */
public class FakeShutdownSource : ShutdownSource {
    @Volatile
    private var handler: ((ShutdownRequest) -> Unit)? = null

    @Volatile
    private var chosenExitCode: Int? = null

    private val requested = AtomicBoolean(false)

    @Volatile
    private var laterRequestSent = false

    @Volatile
    private var nextFailure: ShutdownError? = null

    @Volatile
    private var stop: FakeStop? = null

    /** Whether a handler or a stop is installed. */
    public val installed: Boolean get() = handler != null || stop != null

    /**
     * The code the process would have exited with: null until a request arrives, then the `exitCode` given at install,
     * or without one the request's [ShutdownRequest.exitCode]. A handler that throws leaves the request's own code, as
     * the real one does. A later request replaces it with its own code, as a second request ends the process with
     * that code.
     */
    @Volatile
    public var exitCode: Int? = null
        private set

    /** What the handler threw. A real program prints it and still exits; here it is kept instead of printed. */
    @Volatile
    public var thrown: Throwable? = null
        private set

    /**
     * Installs [handler], or answers [ShutdownError.AlreadyInstalled] after a handler or a stop was installed, or else
     * the error [failWith] set.
     *
     * @throws IllegalArgumentException when [exitCode] is outside 0..255, as the real one does.
     */
    override fun onShutdownRequest(
        exitCode: Int?,
        handler: (ShutdownRequest) -> Unit,
    ): EmptyResult<ShutdownError> = install(exitCode) { this.handler = handler }

    /**
     * Installs a stop that [request] sets, or answers [ShutdownError.AlreadyInstalled] after a handler or a stop was
     * installed, or else the error [failWith] set.
     *
     * @throws IllegalArgumentException when [exitCode] is outside 0..255, as the real one does.
     */
    override fun stopOnShutdownRequest(exitCode: Int?): Result<ShutdownStop, ShutdownError> = install(exitCode) {
        FakeStop().also { stop = it }
    }

    private fun <T> install(
        exitCode: Int?,
        installing: () -> T,
    ): Result<T, ShutdownError> {
        require(exitCode == null || exitCode in 0..255) { "exitCode must be in 0..255, was $exitCode" }
        if (installed) return Err(ShutdownError.AlreadyInstalled)
        nextFailure?.let { failure ->
            nextFailure = null
            return Err(failure)
        }
        chosenExitCode = exitCode
        return Ok(installing())
    }

    /**
     * Makes the next [onShutdownRequest] or [stopOnShutdownRequest] that is not [ShutdownError.AlreadyInstalled] return
     * [error] and install nothing; the call after it installs as usual.
     */
    public fun failWith(error: ShutdownError) {
        nextFailure = error
    }

    /**
     * Sends [request]: the first one runs the installed handler on the calling thread, or sets the installed stop. Any
     * later one, like one sent with nothing installed, only records its exit code, as the process would end at once.
     */
    public fun request(request: ShutdownRequest) {
        val first = requested.compareAndSet(expectedValue = false, newValue = true)
        val stop = stop
        if (first && stop != null) {
            stop.request = request
            exitCode = chosenExitCode ?: request.exitCode
            return
        }
        val handler = handler
        if (!first || handler == null) {
            if (!first) laterRequestSent = true
            exitCode = request.exitCode
            return
        }
        exitCode = chosenExitCode ?: request.exitCode
        try {
            handler(request)
        } catch (e: Throwable) {
            thrown = e
            if (!laterRequestSent) exitCode = request.exitCode
        }
    }

    private class FakeStop : ShutdownStop {
        @Volatile
        override var request: ShutdownRequest? = null

        override val requested: Boolean get() = request != null
    }
}
