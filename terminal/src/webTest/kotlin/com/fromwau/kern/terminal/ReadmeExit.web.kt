package com.fromwau.kern.terminal

import kotlin.js.ExperimentalWasmJsInterop

actual fun exit(code: Int): Nothing {
    processExit(code)
    error("process.exit returned")
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun processExit(code: Int): Unit = js("process.exit(code)")
