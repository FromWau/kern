package com.fromwau.kern.dirs

// iOS reads no environment variable, so there is nothing to be strict about.
internal actual fun currentPlatformFactory(strict: Boolean): BaseDirsFactory = BaseDirsFactory()
