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
 *
 * [runtimeHome] is `$XDG_RUNTIME_DIR` on Linux, a folder only this user may enter that is emptied at logout,
 * for sockets, PID files and locks. It is null where that variable is unset and on every other platform, which
 * has no folder with those guarantees.
 *
 * [naming] is how this platform names an app's folder from an [AppId], set by the factory.
 */
public data class BaseDirs(
    val home: Path,
    val configHome: Path,
    val dataHome: Path,
    val stateHome: Path,
    val cacheHome: Path,
    val tempHome: Path,
    val runtimeHome: Path? = null,
    val naming: AppNaming = AppNaming.Xdg,
) {
    /**
     * Each root with [name] appended; on Linux, `forApp("echod")` gives `~/.config/echod` and its siblings. Where
     * the cache and state roots are one folder, as on Windows, cache is a `cache` folder inside the app's state
     * folder. Call this once per run and pass the result around, since every call draws a new [runId] by default.
     *
     * @param name the app's folder name under each root, which has to be one folder name: a blank name, `.`,
     *   `..` or a name holding `/` or `\` is [DirsError.InvalidAppName].
     * @param runId the last segment of [AppDirs.temp], so every run gets a scratch folder of its own. Pass a fixed
     *   id to reuse one.
     * @return the app's directories. They are only computed: nothing is created.
     */
    public fun forApp(
        name: String,
        runId: Uuid = Uuid.random(),
    ): Result<AppDirs, DirsError> = forFolders(listOf(name), runId)

    /**
     * Each root with [id]'s folder appended, named the way this platform names an app's folder ([naming]):
     * `AppId("com", "Foo Corp", "Bar App")` is `barapp` on Linux, `com.Foo-Corp.Bar-App` on macOS's own layout and
     * `Foo Corp\Bar App` on Windows. Otherwise as [forApp] with a name.
     *
     * @return the app's directories, or [DirsError.InvalidAppName] naming the folder when one would not be a
     *   single folder name.
     */
    public fun forApp(
        id: AppId,
        runId: Uuid = Uuid.random(),
    ): Result<AppDirs, DirsError> =
        // Checked before naming, which drops empty parts and would leave the organization's folder.
        if (id.application.isOneFolderName()) {
            forFolders(naming.foldersOf(id), runId)
        } else {
            Err(DirsError.InvalidAppName(id.application))
        }

    private fun forFolders(
        folders: List<String>,
        runId: Uuid,
    ): Result<AppDirs, DirsError> {
        folders.firstOrNull { !it.isOneFolderName() }?.let { return Err(DirsError.InvalidAppName(it)) }
        val under = { root: Path -> folders.fold(root) { path, folder -> Path(path, folder) } }
        return Ok(
            AppDirs(
                config = under(configHome),
                data = under(dataHome),
                state = under(stateHome),
                cache = if (cacheHome == stateHome) Path(under(stateHome), "cache") else under(cacheHome),
                temp = Path(under(tempHome), runId.toString()),
                runtime = runtimeHome?.let(under),
            ),
        )
    }
}

/** Whether this names exactly one folder below a root, rather than none, the root's parent or several. */
private fun String.isOneFolderName(): Boolean =
    isNotBlank() && this != "." && this != ".." && '/' !in this && '\\' !in this

/**
 * One app's directories. Outside the XDG layout (Linux, or macOS under [DirLayout.Xdg]), config and data are the
 * same directory, and so is state on macOS and iOS. Cache never shares a directory with state, so clearing it
 * never loses state.
 *
 * [temp] is this run's own scratch folder, `tempHome/<name>/<runId>`. Like every path here it is only
 * computed: create it when it is needed, and clear it on shutdown, with `deleteRecursively` from `kern:fs`.
 *
 * [runtime] is null wherever [BaseDirs.runtimeHome] is.
 */
public data class AppDirs(
    val config: Path,
    val data: Path,
    val state: Path,
    val cache: Path,
    val temp: Path,
    val runtime: Path? = null,
)

/**
 * Which root [DirsError.UnableToResolve] is about. [Config] also stands for data and state wherever they share
 * its directory, and [Cache] for state on Windows, where both are `%LOCALAPPDATA%`.
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
     * ([DirKind.Cache]) or `%TEMP%` ([DirKind.Temp]). Apple native's own layout can miss [DirKind.Config] or
     * [DirKind.Cache], and Android never fails.
     */
    public data class UnableToResolve(val kind: DirKind) : DirsError

    /**
     * The name given to [BaseDirs.forApp], or a folder an [AppId] makes, is not one folder name: it is blank, `.`,
     * `..` or holds a separator.
     */
    public data class InvalidAppName(val name: String) : DirsError
}
