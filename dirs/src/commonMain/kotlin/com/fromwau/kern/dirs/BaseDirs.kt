package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.ResultError
import kotlinx.io.files.Path
import kotlin.uuid.Uuid

/**
 * The platform's roots for each kind of app file, before any app name is applied. Get the current
 * platform's from [BaseDirsFactory.create]. To override one root, copy it: `base.copy(stateHome = override)`.
 *
 * [home] is `$HOME` on Linux, or the account's home when that is unset, blank or relative. It is the account's
 * home on macOS, except in a sandboxed native app, where it is the app's container; the user profile on Windows;
 * `filesDir` on Android; and the app's sandbox on iOS.
 *
 * [tempHome] is where scratch files go: `$TMPDIR` on Linux and on the JVM on Linux and macOS, falling back to `/tmp`;
 * the folder Foundation reports on macOS and iOS native, which is the container's inside a sandbox; `%TEMP%` on
 * Windows, native or JVM; and `tmp` in the app's cache directory on Android.
 *
 * [runtimeHome] is `$XDG_RUNTIME_DIR` on Linux, a folder only this user may enter that is emptied at logout,
 * for sockets and other per-session files. It is taken only when it names an existing folder this user owns with no
 * group or other permission bits, as the XDG Base Directory spec requires of it. Otherwise, or when unset, or off
 * Linux, it is null; a value that was refused is listed in [ignored]. No other platform has a folder with those
 * guarantees.
 *
 * [naming] is how this platform names an app's folder from an [AppId], set by the factory.
 *
 * [ignored] lists each environment variable the factory read as unset because of its value, with the reason: one set
 * but blank, one set to something other than an absolute path, such as a relative `XDG_CONFIG_HOME=cfg`, or an
 * `XDG_RUNTIME_DIR` that names no private folder. An unset variable is not listed. It is always empty on Android and
 * iOS, which read none, and from a factory made with `strict = true`, which answers [DirsError.Blank],
 * [DirsError.NotAbsolute] or [DirsError.NotPrivate] instead.
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
    val ignored: List<IgnoredVariable> = emptyList(),
) {
    /**
     * Each root with [name] appended; on Linux, `forApp("echod")` gives `~/.config/echod` and its siblings. Where
     * the cache and state roots are one folder, as on Windows, cache is a `cache` folder inside the app's state
     * folder. Call this once per run and pass the result around, since every call draws a new [runId] by default.
     *
     * @param name the app's folder name under each root, which has to be one folder name: a blank name, `.`,
     *   `..`, a name holding `/`, `\` or a NUL, or one longer than 255 bytes in UTF-8 is [DirsError.InvalidAppName].
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
                ignored = ignored,
            ),
        )
    }

    public companion object {
        /**
         * Every root in a folder of its own below [root], such as a test's scratch folder: `home`, `config`, `data`,
         * `state`, `cache`, `tmp` and `run`, the last as [BaseDirs.runtimeHome]. Nothing is created, and
         * [BaseDirs.ignored] is empty.
         *
         * ```kotlin
         * val dirs = BaseDirs.under(scratch).forApp("myapp").getOrElse { error("not one folder name: $it") }
         * ```
         *
         * @param naming how [BaseDirs.forApp] names an app's folder from an [AppId].
         */
        public fun under(
            root: Path,
            naming: AppNaming = AppNaming.Xdg,
        ): BaseDirs = BaseDirs(
            home = Path(root, "home"),
            configHome = Path(root, "config"),
            dataHome = Path(root, "data"),
            stateHome = Path(root, "state"),
            cacheHome = Path(root, "cache"),
            tempHome = Path(root, "tmp"),
            runtimeHome = Path(root, "run"),
            naming = naming,
        )

        /**
         * The roots the Linux factory resolves, read from [env] instead of this process's environment, so a test can
         * check how an app starts under any set of variables. Each variable is read as the Linux factory reads it,
         * `strict` included. macOS under [DirLayout.Xdg] takes the same home and XDG roots, but keeps the system's temp
         * folder and has no runtime root.
         *
         * ```kotlin
         * val base = BaseDirs.fromXdg(mapOf("HOME" to "/home/test"), accountHome = null).getOrElse { error("$it") }
         * ```
         *
         * It touches the file system only to check that the folder `XDG_RUNTIME_DIR` names is private, as the Linux
         * factory does. That check can pass on Linux, native or on the JVM, on Apple native and on Android; on the JVM
         * on macOS and on Windows the folder counts as not private.
         *
         * @param env the environment variables, by name. A blank value counts as unset and is listed in
         *   [BaseDirs.ignored] as [IgnoredBecause.Blank], or is [DirsError.Blank] with [strict]: a blank `HOME` then
         *   is that error even when [accountHome] would give a home.
         * @param accountHome the account's own home, which the factory reads from the account database on native Linux
         *   and from `user.home` on the JVM, taken when `HOME` is unset, blank or relative. One that is not absolute
         *   counts as missing.
         * @param strict whether a blank variable is [DirsError.Blank], one set to something other than an absolute
         *   path [DirsError.NotAbsolute], and an `XDG_RUNTIME_DIR` that names no private folder [DirsError.NotPrivate],
         *   rather than listed in [BaseDirs.ignored].
         * @return the roots; [DirsError.UnableToResolve] with [DirKind.Home] when neither `HOME` nor [accountHome]
         *   gives a home; or, when [strict], the error for the first variable it would list in [BaseDirs.ignored].
         */
        public fun fromXdg(
            env: Map<String, String>,
            accountHome: Path?,
            strict: Boolean = false,
        ): Result<BaseDirs, DirsError> = linuxBaseDir(env::get, { accountHome?.toString()?.asRoot() }, strict)
    }
}

/** An environment variable a [BaseDirsFactory] read as unset because of its [value], for the [reason] given. */
public data class IgnoredVariable(
    val name: String,
    val value: String,
    val reason: IgnoredBecause,
)

/** Why a [BaseDirsFactory] read an environment variable as unset. */
public enum class IgnoredBecause {
    /** The value is not an absolute path, so it would move with the working directory. */
    NotAbsolute,

    /**
     * `XDG_RUNTIME_DIR` names no folder that this user owns and that its group and others may not enter: it is
     * missing, not a folder, another user's, or has group or other permission bits.
     */
    NotPrivateFolder,

    /** The variable is set, but empty or only whitespace. */
    Blank,
}

/**
 * The error a factory made with `strict = true` answers for this variable instead of listing it in [BaseDirs.ignored]:
 * [DirsError.NotAbsolute] for [IgnoredBecause.NotAbsolute], [DirsError.NotPrivate] for
 * [IgnoredBecause.NotPrivateFolder], and [DirsError.Blank] for [IgnoredBecause.Blank], each naming the variable and
 * its value.
 *
 * ```kotlin
 * val refused = base.ignored.firstOrNull { it.reason != IgnoredBecause.NotPrivateFolder }?.toError()
 * ```
 */
public fun IgnoredVariable.toError(): DirsError = when (reason) {
    IgnoredBecause.NotAbsolute -> DirsError.NotAbsolute(name, value)
    IgnoredBecause.NotPrivateFolder -> DirsError.NotPrivate(name, value)
    IgnoredBecause.Blank -> DirsError.Blank(name, value)
}

/** Whether this names exactly one folder below a root, rather than none, the root's parent or several. */
private fun String.isOneFolderName(): Boolean =
    // kern:fs's nameProblem, which this module cannot depend on.
    isNotBlank() &&
        this != "." &&
        this != ".." &&
        '/' !in this &&
        '\\' !in this &&
        '\u0000' !in this &&
        encodeToByteArray().size <= LONGEST_NAME_BYTES

/** The longest name a folder may take, in UTF-8 bytes, as kern:fs's `LONGEST_NAME_BYTES` allows. */
private const val LONGEST_NAME_BYTES = 255

/**
 * One app's directories. Outside the XDG layout (Linux, or macOS under [DirLayout.Xdg]), config and data are the
 * same directory, and so is state on macOS and iOS. Cache never shares a directory with state, so clearing it
 * never loses state.
 *
 * [temp] is this run's own scratch folder, `tempHome/<name>/<runId>`. Like every path here it is only
 * computed: `withRunTemp` from `kern:fs` creates it, claims it for this run and clears it when the run is done.
 *
 * [runtime] is null wherever [BaseDirs.runtimeHome] is.
 *
 * [ignored] is [BaseDirs.ignored] of the roots these came from, so an app holding only these can still report a
 * variable the factory read as unset.
 */
public data class AppDirs(
    val config: Path,
    val data: Path,
    val state: Path,
    val cache: Path,
    val temp: Path,
    val runtime: Path? = null,
    val ignored: List<IgnoredVariable> = emptyList(),
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
public sealed interface DirsError : ResultError {
    /**
     * The environment or the OS had no answer for the [kind] root. Linux, and macOS on the JVM, can only miss
     * [DirKind.Home]. Windows can miss [DirKind.Home], `%APPDATA%` ([DirKind.Config]), `%LOCALAPPDATA%`
     * ([DirKind.Cache]) or `%TEMP%` ([DirKind.Temp]). Apple native's own layout can miss [DirKind.Config] or
     * [DirKind.Cache], and Android never fails. [ignored] lists the variables read as unset on the way, as
     * [BaseDirs.ignored] does, such as a relative `%APPDATA%` behind a missing [DirKind.Config].
     */
    public data class UnableToResolve(
        val kind: DirKind,
        val ignored: List<IgnoredVariable> = emptyList(),
    ) : DirsError

    /**
     * The name given to [BaseDirs.forApp], or a folder an [AppId] makes, is not one folder name: it is blank, `.`,
     * `..`, holds a separator or a NUL, or is longer than 255 bytes in UTF-8.
     */
    public data class InvalidAppName(val name: String) : DirsError

    /**
     * A factory made with `strict = true` read the environment variable [name] as [value], which is not an absolute
     * path, where one that is not strict would list it in [BaseDirs.ignored] and count it as unset.
     */
    public data class NotAbsolute(
        val name: String,
        val value: String,
    ) : DirsError

    /**
     * A factory made with `strict = true` read `XDG_RUNTIME_DIR`, as [name], as [value], which names no folder this
     * user owns and its group and others may not enter. A factory that is not strict lists it in [BaseDirs.ignored]
     * as [IgnoredBecause.NotPrivateFolder] instead and leaves [BaseDirs.runtimeHome] null.
     */
    public data class NotPrivate(
        val name: String,
        val value: String,
    ) : DirsError

    /**
     * A factory made with `strict = true` read the environment variable [name] as [value], which is empty or only
     * whitespace, where one that is not strict would list it in [BaseDirs.ignored] as [IgnoredBecause.Blank] and count
     * it as unset.
     */
    public data class Blank(
        val name: String,
        val value: String,
    ) : DirsError
}
