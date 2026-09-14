package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.IError
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/**
 * The platform's roots for each kind of app file, before any app name is applied. Get the current
 * platform's from [BaseDirsFactory.create]. To override one root, copy it: `base.copy(stateHome = override)`.
 *
 * [home] is `$HOME` on Linux, where the JVM falls back to `user.home` when it is unset. It is the account's home
 * on macOS, except in a sandboxed native app, where it is the app's container; the user profile on Windows;
 * `filesDir` on Android; and the app's sandbox on iOS.
 *
 * [tempHome] is where scratch files go: `$TMPDIR` on Linux and the JVM, falling back to `/tmp`; the folder
 * Foundation reports on macOS and iOS native, which is the container's inside a sandbox; `%TEMP%` on Windows;
 * and `tmp` in the app's cache directory on Android.
 */
public data class BaseDirs(
    val home: Path,
    val configHome: Path,
    val dataHome: Path,
    val stateHome: Path,
    val cacheHome: Path,
    val tempHome: Path,
) {
    /**
     * Each root with [name] appended; on Linux, `forApp("echod")` gives `~/.config/echod` and its siblings. Call
     * this once per run and pass the result around, since every call draws a new [runId] by default.
     *
     * @param name the app's folder name under each root. A blank name is [DirsError.InvalidAppName].
     * @param runId the last segment of [AppDirs.temp], so every run gets a scratch folder of its own. Pass a fixed
     *   id to reuse one.
     * @return the app's directories. They are only computed: nothing is created.
     */
    public fun forApp(
        name: String,
        runId: Uuid = Uuid.random(),
    ): Result<AppDirs, DirsError> =
        if (name.isNotBlank()) {
            Ok(
                AppDirs(
                    config = Path(configHome, name),
                    data = Path(dataHome, name),
                    state = Path(stateHome, name),
                    cache = Path(cacheHome, name),
                    temp = Path(tempHome, name, runId.toString()),
                ),
            )
        } else {
            Err(DirsError.InvalidAppName(name))
        }
}

/**
 * One app's directories. Outside Linux, config and data are the same directory, and so is state except on
 * Android. Cache never shares a directory with state, so clearing it never loses state.
 *
 * [temp] is this run's own scratch folder, `tempHome/<name>/<runId>`. Like every path here it is only
 * computed: create it when it is needed and clear it with [deleteRecursively] on shutdown.
 */
public data class AppDirs(
    val config: Path,
    val data: Path,
    val state: Path,
    val cache: Path,
    val temp: Path,
)

/**
 * Which root [DirsError.UnableToResolve] is about. [Config] also stands for data and state wherever they share
 * its directory.
 */
public enum class DirKind {
    Home,
    Config,
    Cache,
    Temp,
}

/** Why [BaseDirsFactory.create] or [BaseDirs.forApp] failed. */
public sealed interface DirsError : IError {
    /**
     * The environment or the OS had no answer for the [kind] root. Linux, and macOS on the JVM, can only miss
     * [DirKind.Home]. Windows can miss [DirKind.Home], `%APPDATA%` ([DirKind.Config]), `%LOCALAPPDATA%`
     * ([DirKind.Cache]) or `%TEMP%` ([DirKind.Temp]). Apple native can miss [DirKind.Config] or [DirKind.Cache],
     * and Android never fails.
     */
    public data class UnableToResolve(val kind: DirKind) : DirsError

    /** The name given to [BaseDirs.forApp] is blank. */
    public data class InvalidAppName(val name: String) : DirsError
}
