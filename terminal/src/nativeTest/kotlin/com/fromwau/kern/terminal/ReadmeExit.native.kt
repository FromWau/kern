package com.fromwau.kern.terminal

import kotlin.system.exitProcess

actual fun exit(code: Int): Nothing = exitProcess(code)
