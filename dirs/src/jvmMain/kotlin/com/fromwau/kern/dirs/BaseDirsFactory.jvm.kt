package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

/** @param layout which convention macOS uses; Linux and Windows have one each and ignore it. */
public actual class BaseDirsFactory(private val layout: DirLayout = DirLayout.Native) {
    public actual fun create(): Result<BaseDirs, DirsError> =
        baseDirFor(
            osName = System.getProperty("os.name").orEmpty(),
            env = System::getenv,
            userHome = System.getProperty("user.home"),
            layout = layout,
        )
}

/**
 * Picks the rules for [osName]. [env] reads the raw environment; a blank value counts as unset. A [userHome]
 * that is not an absolute path counts as unset as well: the JDK reports `?` when it cannot find the account.
 */
internal fun baseDirFor(
    osName: String,
    env: (String) -> String?,
    userHome: String?,
    layout: DirLayout = DirLayout.Native,
): Result<BaseDirs, DirsError> {
    val variable = { name: String -> env(name)?.takeIf { it.isNotBlank() } }
    val home = userHome?.takeIf { Path(it).isAbsolute }
    val os = osName.lowercase()
    return when {
        // Checked before "win", which "darwin" contains.
        "mac" in os || "darwin" in os -> when (layout) {
            DirLayout.Native -> appleBaseDir(variable, home)
            // $HOME first, as on Linux: XDG's defaults are relative to it, and user.home ignores it.
            DirLayout.Xdg -> (variable("HOME") ?: home)
                ?.let { Ok(xdgBaseDirs(it, variable, tempRoot(variable))) }
                ?: Err(DirsError.UnableToResolve(DirKind.Home))
        }
        "win" in os -> windowsBaseDir(variable, home)
        else -> linuxBaseDir(variable, home)
    }
}

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

internal fun linuxBaseDir(
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    // user.home comes from the account database and ignores $HOME, which XDG's defaults are relative to.
    val home = env("HOME") ?: userHome ?: return Err(DirsError.UnableToResolve(DirKind.Home))

    return Ok(xdgBaseDirs(home, env, tempRoot(env)).copy(runtimeHome = env("XDG_RUNTIME_DIR")?.let(::Path)))
}

internal fun appleBaseDir(
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    val home = userHome ?: return Err(DirsError.UnableToResolve(DirKind.Home))
    val support = Path("$home/Library/Application Support")

    return Ok(
        BaseDirs(
            home = Path(home),
            configHome = support,
            dataHome = support,
            stateHome = support,
            cacheHome = Path("$home/Library/Caches"),
            tempHome = tempRoot(env),
            naming = AppNaming.Apple,
        ),
    )
}

/** Where scratch files go on Linux and macOS: `$TMPDIR`, else `/tmp`. */
private fun tempRoot(env: (String) -> String?): Path = Path(env("TMPDIR") ?: "/tmp")
