package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

public actual class BaseDirsFactory {
    public actual fun create(): Result<BaseDirs, DirsError> {
        val home = env("HOME") ?: return Err(DirsError.UnableToResolve(DirKind.Home))

        return Ok(
            BaseDirs(
                home = Path(home),
                configHome = Path(env("XDG_CONFIG_HOME") ?: "$home/.config"),
                dataHome = Path(env("XDG_DATA_HOME") ?: "$home/.local/share"),
                stateHome = Path(env("XDG_STATE_HOME") ?: "$home/.local/state"),
                cacheHome = Path(env("XDG_CACHE_HOME") ?: "$home/.cache"),
                tempHome = Path(env("TMPDIR") ?: "/tmp"),
            ),
        )
    }
}
