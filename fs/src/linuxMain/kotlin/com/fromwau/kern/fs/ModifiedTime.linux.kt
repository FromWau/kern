@file:OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)

package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.posix.stat
import kotlin.time.Instant

// Linux names the fields st_mtim and st_ctim, and Apple st_mtimespec and st_ctimespec, so the two cannot share
// posixMain.
internal actual fun stat.modifiedAt(): Instant = Instant.fromEpochSeconds(st_mtim.tv_sec, st_mtim.tv_nsec)

internal actual fun stat.changedAt(): Instant = Instant.fromEpochSeconds(st_ctim.tv_sec, st_ctim.tv_nsec)

// glibc's UTIME_OMIT, which the linuxX64 bindings leave out.
internal actual val OMIT_TIME: Long = (1L shl 30) - 2
