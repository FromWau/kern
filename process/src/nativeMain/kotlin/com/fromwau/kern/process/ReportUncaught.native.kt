@file:OptIn(ExperimentalNativeApi::class)

package com.fromwau.kern.process

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.getUnhandledExceptionHook

/** The app's unhandled exception hook, or a print to standard error without one. */
internal actual fun reportUncaught(e: Throwable) {
    val hook = getUnhandledExceptionHook()
    if (hook != null) hook(e) else e.printStackTrace()
}
