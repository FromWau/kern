package com.fromwau.kern.dirs

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

@OptIn(ExperimentalForeignApi::class)
internal fun env(name: String): String? = getenv(name)?.toKString()?.takeIf { it.isNotBlank() }
