package com.fromwau.kern.dirs

import com.fromwau.kern.result.Result

/**
 * This platform's roots in its default layout, as `BaseDirsFactory().create()` gives them, callable from common code
 * of an app that has no Android target. An app with an Android target makes its own [BaseDirsFactory] in platform
 * code, since Android's needs a `Context`.
 *
 * ```kotlin
 * val dirs = BaseDirs.current().flatMap { it.forApp("myapp") }.getOrElse { error("no folders for myapp: $it") }
 * ```
 *
 * @param strict whether a blank variable, one set to something other than an absolute path, or on Linux an
 *   `XDG_RUNTIME_DIR` that names no private folder, is an error rather than listed in [BaseDirs.ignored], as
 *   `BaseDirsFactory(strict = true)` makes it. iOS reads no variable and ignores it.
 * @return the roots, or the [DirsError] the platform's factory answers.
 */
public fun BaseDirs.Companion.current(strict: Boolean = false): Result<BaseDirs, DirsError> =
    currentPlatformFactory(strict).create()

/** This platform's [BaseDirsFactory] with its default layout, made [strict] where the platform reads any variable. */
internal expect fun currentPlatformFactory(strict: Boolean): BaseDirsFactory
