package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

public actual class BaseDirsFactory {
    public actual fun create(): Result<BaseDirs, DirsError> =
        baseDirFor(
            osName = System.getProperty("os.name").orEmpty(),
            env = System::getenv,
            userHome = System.getProperty("user.home"),
        )
}

/** Picks the rules for [osName]. [env] reads the raw environment; a blank value counts as unset. */
internal fun baseDirFor(
    osName: String,
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    val variable = { name: String -> env(name)?.takeIf { it.isNotBlank() } }
    val os = osName.lowercase()
    return when {
        // Checked before "win", which "darwin" contains.
        "mac" in os || "darwin" in os -> appleBaseDir(variable, userHome)
        "win" in os -> windowsBaseDir(variable, userHome)
        else -> linuxBaseDir(variable, userHome)
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
            stateHome = Path(appData),
            cacheHome = Path(localAppData),
            tempHome = Path(temp),
        ),
    )
}

internal fun linuxBaseDir(
    env: (String) -> String?,
    userHome: String?,
): Result<BaseDirs, DirsError> {
    // user.home comes from the account database and ignores $HOME, which XDG's defaults are relative to.
    val home = env("HOME") ?: userHome ?: return Err(DirsError.UnableToResolve(DirKind.Home))

    return Ok(
        BaseDirs(
            home = Path(home),
            configHome = Path(env("XDG_CONFIG_HOME") ?: "$home/.config"),
            dataHome = Path(env("XDG_DATA_HOME") ?: "$home/.local/share"),
            stateHome = Path(env("XDG_STATE_HOME") ?: "$home/.local/state"),
            cacheHome = Path(env("XDG_CACHE_HOME") ?: "$home/.cache"),
            tempHome = tempRoot(env),
        ),
    )
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
        ),
    )
}

/** Where scratch files go on Linux and macOS: `$TMPDIR`, else `/tmp`. */
private fun tempRoot(env: (String) -> String?): Path = Path(env("TMPDIR") ?: "/tmp")
