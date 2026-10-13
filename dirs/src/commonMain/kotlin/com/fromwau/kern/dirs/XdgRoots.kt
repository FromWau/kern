package com.fromwau.kern.dirs

import kotlinx.io.files.Path

/** The XDG roots under [home], each taken from its `XDG_*` variable in [env] when that is set. */
internal fun xdgBaseDirs(
    home: String,
    env: (String) -> String?,
    tempHome: Path,
): BaseDirs = BaseDirs(
    home = Path(home),
    configHome = Path(env("XDG_CONFIG_HOME") ?: "$home/.config"),
    dataHome = Path(env("XDG_DATA_HOME") ?: "$home/.local/share"),
    stateHome = Path(env("XDG_STATE_HOME") ?: "$home/.local/state"),
    cacheHome = Path(env("XDG_CACHE_HOME") ?: "$home/.cache"),
    tempHome = tempHome,
)
