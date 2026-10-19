@file:OptIn(ExperimentalWasmJsInterop::class)

package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.js.ExperimentalWasmJsInterop

/**
 * Node's process signal events. A browser has no process to signal. The listener runs to `process.exit` on the event
 * loop, so no second request can arrive while it runs.
 */
internal actual fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError> {
    if (!isNode()) return Err(ShutdownError.Unsupported)
    for (request in ShutdownRequest.entries) {
        onProcessSignal(request.signalName) { exitAfterHandler(request, onRequest, ::processExit) }
    }
    return Ok(Unit)
}

/** Runs [onRequest] and then [exit]s with its code, or with the request's own when [onRequest] throws. */
internal fun exitAfterHandler(
    request: ShutdownRequest,
    onRequest: (ShutdownRequest) -> Int,
    exit: (Int) -> Unit,
) {
    var exitCode = request.exitCode
    try {
        exitCode = onRequest(request)
    } finally {
        exit(exitCode)
    }
}

/** Node's process signal events, recorded; an `'exit'` listener sets the exit code once a request arrived. */
internal actual fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError> {
    if (!isNode()) return Err(ShutdownError.Unsupported)
    val stop = RecordedStop(exitCode)
    for (request in ShutdownRequest.entries) {
        onProcessSignal(request.signalName) {
            stop.onRequest(request)?.let(::processExit)
        }
    }
    onProcessExit {
        stop.exitCodeAtExit()?.let(::setProcessExitCode)
    }
    return Ok(stop)
}

/** A stop as Node's single thread records it. */
internal class RecordedStop(private val exitCode: Int?) : ShutdownStop {
    override var request: ShutdownRequest? = null
        private set

    private var laterRequest = false

    override val requested: Boolean get() = request != null

    /** Records the first request and returns null, or returns the code a later request exits with at once. */
    fun onRequest(request: ShutdownRequest): Int? {
        if (this.request != null) {
            laterRequest = true
            return request.exitCode
        }
        this.request = request
        return null
    }

    /** The code to set as the process exits, or null to leave the exit's own. */
    fun exitCodeAtExit(): Int? = if (laterRequest) null else request?.let { exitCode ?: it.exitCode }
}

private val ShutdownRequest.signalName: String
    get() = when (this) {
        ShutdownRequest.Interrupt -> "SIGINT"
        ShutdownRequest.Terminate -> "SIGTERM"
        ShutdownRequest.Hangup -> "SIGHUP"
    }

private fun isNode(): Boolean =
    js("typeof process !== 'undefined' && process.versions != null && typeof process.versions.node === 'string'")

private fun onProcessSignal(name: String, listener: () -> Unit): Unit = js("{ process.on(name, listener); }")

private fun processExit(code: Int): Unit = js("process.exit(code)")

private fun onProcessExit(listener: () -> Unit): Unit = js("{ process.on('exit', listener); }")

private fun setProcessExitCode(code: Int): Unit = js("{ process.exitCode = code; }")
