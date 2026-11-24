package com.fromwau.kern.process

/** Prints [e] to standard error the way Kotlin/Native prints an uncaught exception. */
internal fun printUncaught(e: Throwable) {
    e.printStackTrace()
}
