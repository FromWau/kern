package com.fromwau.kern.dirs

import com.fromwau.kern.result.Result

/**
 * Resolves the current platform's [BaseDirs]. Common code can hold and call one; only platform code
 * constructs it, because Android's needs a `Context`:
 *
 * ```kotlin
 * // androidMain
 * val factory = BaseDirsFactory(context)
 * // every other target
 * val factory = BaseDirsFactory()
 * // common code
 * factory.create().flatMap { it.forApp("myapp") }
 * ```
 */
public expect class BaseDirsFactory {
    /**
     * Reads the environment and asks the OS where its directories are. It never creates or touches them,
     * except on Android, where the Context's own directory getters create the app's sandbox directories
     * when they are missing.
     */
    public fun create(): Result<BaseDirs, DirsError>
}
