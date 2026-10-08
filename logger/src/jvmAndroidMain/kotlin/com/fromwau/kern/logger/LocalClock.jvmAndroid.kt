package com.fromwau.kern.logger

// The JVM reads TZ itself when it starts, the Java way.
internal actual fun systemLocalClock(): LocalClock = kotlinxSystemClock()
