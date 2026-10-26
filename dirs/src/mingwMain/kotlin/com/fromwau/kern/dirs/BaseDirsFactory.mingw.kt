package com.fromwau.kern.dirs

import com.fromwau.kern.result.Result

/**
 * @param strict whether a blank variable is [DirsError.Blank], and one set to something other than an absolute path
 *   [DirsError.NotAbsolute], rather than listed in [BaseDirs.ignored]. A blank `USERPROFILE` is that error even when
 *   `HOME` would give a home.
 */
public actual class BaseDirsFactory(private val strict: Boolean = false) {
    public actual fun create(): Result<BaseDirs, DirsError> {
        val variable = CheckedVariables(::env)
        val home = variable("USERPROFILE") ?: variable("HOME")
        return windowsBaseDir(variable, userHome = home).withIgnored(variable, strict)
    }
}
