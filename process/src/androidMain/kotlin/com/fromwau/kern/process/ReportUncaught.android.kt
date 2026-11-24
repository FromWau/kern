package com.fromwau.kern.process

/**
 * The thread's uncaught exception handler, as on the JVM. Android types it nullable, so without one this falls back
 * the way a JVM thread group does: to the default handler, or a print to standard error without that either.
 */
internal actual fun reportUncaught(e: Throwable) {
    val thread = Thread.currentThread()
    val handler = thread.uncaughtExceptionHandler ?: Thread.getDefaultUncaughtExceptionHandler()
    if (handler != null) handler.uncaughtException(thread, e) else e.printStackTrace()
}
