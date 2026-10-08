@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.datetime.UtcOffset
import platform.posix.localtime_r
import platform.posix.time_tVar
import platform.posix.tm
import platform.posix.tzset

/**
 * The offset comes from the C library, which reads `TZ` the way `date` and every C program do, rules with
 * daylight saving included. kotlinx-datetime natively reads `/etc/localtime` and never `TZ`.
 */
internal actual fun systemLocalClock(): LocalClock {
    tzset()
    return LocalClock { instant ->
        val offsetSeconds = memScoped {
            val seconds = alloc<time_tVar>().apply { value = instant.epochSeconds.convert() }
            val parts = alloc<tm>()
            if (localtime_r(seconds.ptr, parts.ptr) == null) 0 else parts.tm_gmtoff.toInt()
        }
        UtcOffset(seconds = offsetSeconds)
    }
}
