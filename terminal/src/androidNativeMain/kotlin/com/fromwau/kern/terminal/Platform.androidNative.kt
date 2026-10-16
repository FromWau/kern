package com.fromwau.kern.terminal

// cinterop marks the NDK's ioctl as unable to import, so only COLUMNS can set the width here.
internal actual fun platformIo(): PlatformIo = nativePlatformIo(width = null, ansiCapable = true)
