package com.fromwau.kern.process

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

// The README's native samples, compiled here so they keep compiling without warnings. Nothing calls them.

@OptIn(ExperimentalNativeApi::class)
private fun reportCrashes() {
    setUnhandledExceptionHook { e -> println("crashed: $e") }
}
