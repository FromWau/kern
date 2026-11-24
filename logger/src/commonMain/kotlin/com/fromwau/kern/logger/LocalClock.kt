@file:OptIn(ExperimentalAtomicApi::class)

package com.fromwau.kern.logger

import kotlinx.datetime.IllegalTimeZoneException
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.offsetAt
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant

/** The local offset from UTC at an instant, which a text line is stamped with. */
internal fun interface LocalClock {
    fun offsetAt(instant: Instant): UtcOffset
}

/** The system's local clock as it is now; each text-rendering sink reads it once, on its first entry. */
internal expect fun systemLocalClock(): LocalClock

internal fun TimeZone.asLocalClock(): LocalClock = LocalClock { offsetAt(it) }

/**
 * The zone kotlinx-datetime reports for the system, or UTC, with one note per process on standard error, when this
 * platform cannot read the system's zone.
 */
internal fun kotlinxSystemClock(
    systemZone: () -> TimeZone = TimeZone::currentSystemDefault,
    fallbackNote: OnceNote = zoneFallbackNote,
): LocalClock {
    val zone = try {
        systemZone()
    } catch (e: Throwable) {
        if (!e.isUnreadableSystemZone()) throw e
        fallbackNote.write("$INTERNAL_TAG: text lines are stamped in UTC: the system time zone could not be read: $e")
        TimeZone.UTC
    }
    return zone.asLocalClock()
}

/**
 * Whether this is how the platform says it cannot read the system's zone: [IllegalTimeZoneException] everywhere, plus
 * what the platform's own zone lookup throws.
 */
internal expect fun Throwable.isUnreadableSystemZone(): Boolean

private val zoneFallbackNote = OnceNote(::reportToStandardErrorOrDrop)

/** Writes its first message through [report] and drops every later one. */
internal class OnceNote(private val report: (String) -> Unit) {
    private val written = AtomicBoolean(false)

    fun write(message: String) {
        if (written.compareAndSet(expectedValue = false, newValue = true)) report(message)
    }
}
