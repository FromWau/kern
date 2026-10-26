package com.fromwau.kern.dirs

internal actual fun currentPlatformFactory(strict: Boolean): BaseDirsFactory = BaseDirsFactory(strict = strict)
