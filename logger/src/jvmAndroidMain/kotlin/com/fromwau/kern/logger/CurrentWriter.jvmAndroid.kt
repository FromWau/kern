package com.fromwau.kern.logger

private val writer = ThreadLocal<InstalledSink?>()

internal actual var currentWriter: InstalledSink?
    get() = writer.get()
    set(value) = writer.set(value)
