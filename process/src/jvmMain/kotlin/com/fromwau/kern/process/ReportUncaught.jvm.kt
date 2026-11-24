package com.fromwau.kern.process

internal actual fun reportUncaught(e: Throwable) {
    val thread = Thread.currentThread()
    thread.uncaughtExceptionHandler.uncaughtException(thread, e)
}
