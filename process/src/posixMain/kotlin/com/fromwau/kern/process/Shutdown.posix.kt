@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package com.fromwau.kern.process

import com.fromwau.kern.process.signals.kern_process_handler_returned
import com.fromwau.kern.process.signals.kern_process_install
import com.fromwau.kern.process.signals.kern_process_install_stop
import com.fromwau.kern.process.signals.kern_process_request_signal
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.map
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ReportUnhandledExceptionHook
import kotlin.native.setUnhandledExceptionHook
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.EAGAIN
import platform.posix.EMFILE
import platform.posix.ENFILE
import platform.posix.ENOMEM
import platform.posix.SIGHUP
import platform.posix.SIGINT
import platform.posix.SIGTERM

private val handler = AtomicReference<((ShutdownRequest) -> Int)?>(null)
private val previousUnhandledExceptionHook = AtomicReference<ReportUnhandledExceptionHook?>(null)

internal actual fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError> {
    handler.store(onRequest)
    return installed(kern_process_install(staticCFunction(::onSignal)))
}

internal actual fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError> =
    installed(kern_process_install_stop(exitCode ?: -1)).map { SignalStop }

private object SignalStop : ShutdownStop {
    override val requested: Boolean get() = kern_process_request_signal() != 0
    override val request: ShutdownRequest? get() = requestFor(kern_process_request_signal())
}

/** Maps the C install's errno to its result, chaining kern's crash hook once installed. */
private fun installed(failure: Int): EmptyResult<ShutdownError> = when (failure) {
    0 -> {
        previousUnhandledExceptionHook.store(setUnhandledExceptionHook(::onUnhandledException))
        Ok(Unit)
    }
    EMFILE, ENFILE, EAGAIN, ENOMEM -> Err(ShutdownError.OutOfResources)
    else -> Err(ShutdownError.System(failure))
}

private fun requestFor(signal: Int): ShutdownRequest? = when (signal) {
    SIGINT -> ShutdownRequest.Interrupt
    SIGTERM -> ShutdownRequest.Terminate
    SIGHUP -> ShutdownRequest.Hangup
    else -> null
}

/** Runs on the C reader thread, outside any signal handler, for the first request only. */
private fun onSignal(signal: Int) {
    val request = requestFor(signal) ?: error("no shutdown request for signal $signal")
    // Only printing what the handler threw can throw here. Escaping this C callback would abort the process, so the
    // failure ends it with the request's code instead, as a handler that throws does.
    val exitCode = try {
        checkNotNull(handler.load())(request)
    } catch (_: Throwable) {
        request.exitCode
    }
    // An exit that is already waiting for the handler ends the process itself.
    if (kern_process_handler_returned(exitCode) == 0) exitProcess(exitCode)
}

/** An exception escaping `main` after a request ends the process as an exit would: once a handler is done. */
private fun onUnhandledException(e: Throwable) {
    val previous = previousUnhandledExceptionHook.load()
    if (kern_process_request_signal() != 0) {
        if (previous != null) previous(e) else printUncaught(e)
        // The atexit wait replaces this code with the handler's.
        exitProcess(1)
    }
    // Once a hook returns, Kotlin reports the exception and aborts as it does without one.
    previous?.invoke(e)
}
