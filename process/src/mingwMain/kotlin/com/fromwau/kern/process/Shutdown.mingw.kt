@file:OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class)

package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.system.exitProcess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix._exit
import platform.posix.atexit
import platform.windows.CTRL_CLOSE_EVENT
import platform.windows.CTRL_C_EVENT
import platform.windows.DWORD
import platform.windows.FALSE
import platform.windows.GetLastError
import platform.windows.INFINITE
import platform.windows.SetConsoleCtrlHandler
import platform.windows.Sleep
import platform.windows.TRUE
import platform.windows.WINBOOL

private val handler = AtomicReference<((ShutdownRequest) -> Int)?>(null)
private val requested = AtomicBoolean(false)
private val stopRequest = AtomicReference<ShutdownRequest?>(null)
private val stopExitCode = AtomicReference<Int?>(null)
private val exitHookAdded = AtomicBoolean(false)

/** Console control events: Ctrl-C is [ShutdownRequest.Interrupt] and closing the console [ShutdownRequest.Hangup]. */
internal actual fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError> {
    handler.store(onRequest)
    if (SetConsoleCtrlHandler(staticCFunction(::onControlEvent), TRUE) == FALSE) {
        return Err(ShutdownError.System(GetLastError().toInt()))
    }
    return Ok(Unit)
}

private fun requestFor(event: DWORD): ShutdownRequest? = when (event.toInt()) {
    CTRL_C_EVENT -> ShutdownRequest.Interrupt
    CTRL_CLOSE_EVENT -> ShutdownRequest.Hangup
    else -> null
}

/** Windows calls this on a thread it starts for each event, so the handler may block and Kotlin may run here. */
private fun onControlEvent(event: DWORD): WINBOOL {
    val request = requestFor(event) ?: return FALSE
    if (!requested.compareAndSet(expectedValue = false, newValue = true)) _exit(request.exitCode)
    exitProcess(checkNotNull(handler.load())(request))
}

/**
 * Records the first console control event. Windows ends the process once a close event's handler returns, so that
 * handler blocks and lets `main` finish in the time Windows gives it.
 */
internal actual fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError> {
    stopExitCode.store(exitCode)
    // atexit runs each registration, so a second installation, after one that failed further on, adds none.
    val addsHook = exitHookAdded.compareAndSet(expectedValue = false, newValue = true)
    if (addsHook && atexit(staticCFunction(::endStopped)) != 0) {
        exitHookAdded.store(false)
        return Err(ShutdownError.OutOfResources)
    }
    if (SetConsoleCtrlHandler(staticCFunction(::onStopControlEvent), TRUE) == FALSE) {
        return Err(ShutdownError.System(GetLastError().toInt()))
    }
    return Ok(ConsoleStop)
}

private object ConsoleStop : ShutdownStop {
    override val requested: Boolean get() = stopRequest.load() != null
    override val request: ShutdownRequest? get() = stopRequest.load()
}

private fun onStopControlEvent(event: DWORD): WINBOOL {
    val request = requestFor(event) ?: return FALSE
    if (!stopRequest.compareAndSet(expectedValue = null, newValue = request)) _exit(request.exitCode)
    if (request == ShutdownRequest.Hangup) Sleep(INFINITE)
    return TRUE
}

private fun endStopped() {
    val request = stopRequest.load() ?: return
    _exit(stopExitCode.load() ?: request.exitCode)
}
