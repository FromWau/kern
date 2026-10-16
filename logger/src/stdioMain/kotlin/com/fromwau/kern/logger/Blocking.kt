package com.fromwau.kern.logger

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

internal actual fun sinkDispatcher(): CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)

internal actual val threadsCanWait: Boolean = true
