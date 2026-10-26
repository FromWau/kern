@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.posix.UTIME_OMIT
import platform.posix.stat
import kotlin.time.Instant

internal actual fun stat.modifiedAt(): Instant = Instant.fromEpochSeconds(st_mtimespec.tv_sec, st_mtimespec.tv_nsec)

internal actual fun stat.changedAt(): Instant = Instant.fromEpochSeconds(st_ctimespec.tv_sec, st_ctimespec.tv_nsec)

internal actual val OMIT_TIME: Long = UTIME_OMIT.toLong()
