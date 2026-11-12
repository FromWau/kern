package com.fromwau.kern.fs.coroutines

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO

// Each watch waits in a blocking system call for a slice at a time; on Dispatchers.IO itself 64 of them would hold
// every thread it has, so other work there would wait for a slice to end.
internal val WatchWaits: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(Int.MAX_VALUE)
