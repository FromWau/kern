package com.fromwau.kern.logger

import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlin.time.Instant

/** The local offset from UTC at an instant, which a text line is stamped with. */
internal fun interface LocalClock {
    fun offsetAt(instant: Instant): UtcOffset
}

/** The system's local clock as it is now; the logger asks again on every configure. */
internal expect fun systemLocalClock(): LocalClock

internal fun TimeZone.asLocalClock(): LocalClock = LocalClock { offsetAt(it) }

/** The zone kotlinx-datetime reports for the system, or UTC when it cannot name one. */
internal fun kotlinxSystemClock(): LocalClock {
    val zone = try {
        TimeZone.currentSystemDefault()
    } catch (_: Exception) {
        TimeZone.UTC
    }
    return zone.asLocalClock()
}
