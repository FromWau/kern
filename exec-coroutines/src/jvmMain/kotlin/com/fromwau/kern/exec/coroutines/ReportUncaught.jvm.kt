package com.fromwau.kern.exec.coroutines

internal actual fun reportUncaught(e: Throwable) {
    val thread = Thread.currentThread()
    thread.uncaughtExceptionHandler.uncaughtException(thread, e)
}
