package com.fromwau.kern.logger

import kotlinx.datetime.IllegalTimeZoneException

internal actual fun systemLocalClock(): LocalClock = kotlinxSystemClock()

// kotlinx-datetime's registry lookup throws a plain IllegalStateException when Windows reports no zone it knows; the
// exact class, so a subclass such as CancellationException is never taken for it.
internal actual fun Throwable.isUnreadableSystemZone(): Boolean =
    this is IllegalTimeZoneException || this::class == IllegalStateException::class
