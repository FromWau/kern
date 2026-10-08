package com.fromwau.kern.logger

internal actual fun systemLocalClock(): LocalClock = kotlinxSystemClock()
