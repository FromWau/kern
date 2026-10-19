package com.fromwau.kern.process

import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result

/** WASI has no signals. */
internal actual fun installShutdownHandler(onRequest: (ShutdownRequest) -> Int): EmptyResult<ShutdownError> =
    Err(ShutdownError.Unsupported)

internal actual fun installShutdownStop(exitCode: Int?): Result<ShutdownStop, ShutdownError> =
    Err(ShutdownError.Unsupported)
