package com.fromwau.kern.logger

internal actual fun platformWriter(): EntryWriter = terminalWriter()
