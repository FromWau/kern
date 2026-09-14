package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

public actual class BaseDirsFactory {
    public actual fun create(): Result<BaseDirs, DirsError> {
        val home = env("USERPROFILE") ?: env("HOME") ?: return Err(DirsError.UnableToResolve(DirKind.Home))
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
}
