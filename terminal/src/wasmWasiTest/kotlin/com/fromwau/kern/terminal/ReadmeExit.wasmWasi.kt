package com.fromwau.kern.terminal

import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport

actual fun exit(code: Int): Nothing {
    procExit(code)
    error("proc_exit returned")
}

@OptIn(ExperimentalWasmInterop::class)
@WasmImport("wasi_snapshot_preview1", "proc_exit")
private external fun procExit(code: Int)
