package com.fromwau.kern.logger

import kotlin.native.concurrent.ThreadLocal

@ThreadLocal
internal actual var currentWriter: InstalledSink? = null
