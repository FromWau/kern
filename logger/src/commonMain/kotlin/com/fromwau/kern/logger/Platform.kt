package com.fromwau.kern.logger

import kotlinx.coroutines.CoroutineDispatcher

/** Writes one line about the logger's own failure where an operator looks first: standard error. */
internal expect fun reportToStandardError(message: String)

/**
 * Where one sink's calls run, one at a time. On JVM and native, on threads that may block and do not count against
 * the limit of `Dispatchers.IO`; on JS and Wasm, on the single thread.
 */
internal expect fun sinkDispatcher(): CoroutineDispatcher

/**
 * Runs [block] to completion on this thread, or returns null at once where a thread cannot wait (JS, Wasm) or when
 * the thread is interrupted (JVM), whose interrupt it keeps.
 */
internal expect fun <T> runBlockingOrNull(block: suspend () -> T): T?

/** Whether a thread may block at all: false on JS and Wasm, where [runBlockingOrNull] always returns null. */
internal expect val threadsCanWait: Boolean

/** The sink whose write is running on this thread, so an entry it logs is not sent back to it. */
internal expect var currentWriter: InstalledSink?
