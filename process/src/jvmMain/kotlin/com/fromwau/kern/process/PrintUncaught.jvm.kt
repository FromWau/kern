package com.fromwau.kern.process

internal actual fun printUncaught(e: Throwable) {
    System.err.print("Exception in thread \"${Thread.currentThread().name}\" ")
    e.printStackTrace()
}
