package com.fromwau.kern.logger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

internal actual fun sinkDispatcher(): CoroutineDispatcher = Dispatchers.Default

internal actual fun <T> runBlockingOrNull(block: suspend () -> T): T? = null

internal actual val threadsCanWait: Boolean = false

// One thread, and a sink's write never suspends, so no other write can run while this is set.
internal actual var currentWriter: InstalledSink? = null
