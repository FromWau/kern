package com.fromwau.kern.fs

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual fun stat.deviceId(): Long = st_dev.toLong()
