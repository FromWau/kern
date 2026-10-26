package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/**
 * Resolves the current platform's [BaseDirs]. Common code can hold and call one; only platform code
 * constructs it, because Android's needs a `Context`:
 *
 * ```kotlin
 * // androidMain
 * val factory = BaseDirsFactory(context)
 * // jvmMain and macosMain, for a command-line tool that wants the XDG layout on macOS
 * val factory = BaseDirsFactory(DirLayout.Xdg)
 * // every other target
 * val factory = BaseDirsFactory()
 * // JVM, Linux, macOS and Windows native: a relative variable is an error rather than ignored
 * val factory = BaseDirsFactory(strict = true)
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

/** Windows' roots: [userHome] as the home, and the rest from `%APPDATA%`, `%LOCALAPPDATA%` and `%TEMP%`. */
internal fun windowsBaseDir(
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    val home = userHome ?: return Err(DirsError.UnableToResolve(DirKind.Home))
    val appData = env("APPDATA") ?: return Err(DirsError.UnableToResolve(DirKind.Config))
    val localAppData = env("LOCALAPPDATA") ?: return Err(DirsError.UnableToResolve(DirKind.Cache))
    val temp = env("TEMP") ?: return Err(DirsError.UnableToResolve(DirKind.Temp))

    return Ok(
        BaseDirs(
            home = Path(home),
            configHome = Path(appData),
            dataHome = Path(appData),
            stateHome = Path(localAppData),
            cacheHome = Path(localAppData),
            tempHome = Path(temp),
            naming = AppNaming.Windows,
        ),
    )
}

/**
 * Linux's roots from [env]: the XDG rules, with `$TMPDIR`, else `/tmp`, as the temp root and the runtime root checked
 * by [isPrivateFolder], with the variables read as unset attached, or the first of them as the error when [strict].
 */
internal fun linuxBaseDir(
    env: (String) -> String?,
    accountHome: () -> String?,
    strict: Boolean,
    isPrivateFolder: (Path) -> Boolean = ::isPrivateFolder,
): Result<BaseDirs, DirsError> {
    val variables = CheckedVariables(env)
    return resolveXdg(variables, accountHome, { tempRoot(variables) }, isPrivateFolder).withIgnored(variables, strict)
}

/** Where scratch files go on Linux and macOS: `$TMPDIR`, else `/tmp`. */
internal fun tempRoot(env: (String) -> String?): Path = Path(env("TMPDIR") ?: "/tmp")
