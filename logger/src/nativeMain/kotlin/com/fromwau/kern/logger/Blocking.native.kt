package com.fromwau.kern.logger

import kotlinx.coroutines.runBlocking

internal actual fun <T> runBlockingOrNull(block: suspend () -> T): T? = runBlocking { block() }
