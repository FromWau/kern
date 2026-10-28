package com.fromwau.kern.result

import kotlin.native.concurrent.ThreadLocal

@PublishedApi
internal actual abstract class ReusableThrowable actual constructor(message: String) : Throwable(message)

// One per thread on native; JS and Wasm run on one thread.
@ThreadLocal
private var bindSignal = BindSignal()

internal actual fun currentBindSignal(): BindSignal {
    // `use` and coroutines add suppressed exceptions to a throwable, which these targets cannot switch off.
    if (bindSignal.suppressedExceptions.isNotEmpty()) bindSignal = BindSignal()
    return bindSignal
}

internal actual fun isThisThreadsSignal(signal: BindSignal): Boolean = bindSignal === signal
