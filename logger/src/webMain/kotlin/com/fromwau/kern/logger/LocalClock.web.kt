package com.fromwau.kern.logger

import kotlinx.datetime.IllegalTimeZoneException

internal actual fun systemLocalClock(): LocalClock = kotlinxSystemClock()

internal actual fun Throwable.isUnreadableSystemZone(): Boolean = this is IllegalTimeZoneException
