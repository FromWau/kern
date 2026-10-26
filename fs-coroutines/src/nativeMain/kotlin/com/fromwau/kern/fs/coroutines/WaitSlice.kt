package com.fromwau.kern.fs.coroutines

// Kotlin/Native cannot interrupt a thread blocked in a system wait, so a wait is cut into slices this long, between
// which a cancelled collector is let go.
internal const val WAIT_SLICE_MILLIS: Int = 250
