package com.fromwau.kern.result

@PublishedApi
internal actual abstract class ReusableThrowable actual constructor(
    message: String,
) : Throwable(message, null, false, false)

private val bindSignals = ThreadLocal<BindSignal>()

internal actual fun currentBindSignal(): BindSignal = bindSignals.get() ?: BindSignal().also(bindSignals::set)

internal actual fun isThisThreadsSignal(signal: BindSignal): Boolean = bindSignals.get() === signal
