package com.fromwau.kern.process

internal actual fun reportUncaught(e: Throwable) {
    e.printStackTrace()
}
