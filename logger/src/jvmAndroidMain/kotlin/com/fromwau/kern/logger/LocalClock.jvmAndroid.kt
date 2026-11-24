package com.fromwau.kern.logger

import kotlinx.datetime.IllegalTimeZoneException
import java.time.DateTimeException

// The JVM reads TZ itself when it starts, the Java way.
internal actual fun systemLocalClock(): LocalClock = kotlinxSystemClock()

// ZoneId.systemDefault() throws DateTimeException for a default zone id it has no rules for.
internal actual fun Throwable.isUnreadableSystemZone(): Boolean =
    this is IllegalTimeZoneException || this is DateTimeException
