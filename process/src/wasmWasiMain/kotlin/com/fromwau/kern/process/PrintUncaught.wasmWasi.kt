package com.fromwau.kern.process

internal actual fun printUncaught(e: Throwable) {
    e.printStackTrace()
}
