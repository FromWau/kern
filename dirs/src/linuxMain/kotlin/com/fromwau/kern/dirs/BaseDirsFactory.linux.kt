package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path

public actual class BaseDirsFactory {
    public actual fun create(): Result<BaseDirs, DirsError> {
        val home = env("HOME") ?: return Err(DirsError.UnableToResolve(DirKind.Home))

        val roots = xdgBaseDirs(home, ::env, tempHome = Path(env("TMPDIR") ?: "/tmp"))

        return Ok(roots.copy(runtimeHome = env("XDG_RUNTIME_DIR")?.let(::Path)))
    }
}
