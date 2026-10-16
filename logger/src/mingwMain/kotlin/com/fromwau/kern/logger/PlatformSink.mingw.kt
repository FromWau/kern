package com.fromwau.kern.logger

import com.fromwau.kern.result.EmptyResult

internal actual fun platformWriter(): (LogEntry, SinkConfig) -> EmptyResult<SinkError> = terminalWriter()
