# dirs

Every platform keeps an app's config, data, state, cache and temp files in places of its own. `dirs` knows those
places on every Kotlin Multiplatform target and hands them to you as `Path`s. Ask `BaseDirs.current()` for them once
at startup and pass the `AppDirs` down. Reading, writing and locking files under them is [`fs`](../fs/README.md).

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:dirs:$kernVersion")
}
```

- KMP consumers put it in `commonMain`.
- kern's `result` and kotlinx-io come along as `api`. `Result`, `ResultError` and `Path` appear in its signatures.
- Needs what [every kern module needs](../README.md#requirements).

## Rules

- `BaseDirs.current()` gives this platform's roots on every target but Android. `forApp(name)` turns them into an
  `AppDirs` with the app's folders.
- `current` and `toError` are extensions. Each takes an import of its own unless you import
  `com.fromwau.kern.dirs.*`. `BaseDirs.under` and `BaseDirs.fromXdg` come with `BaseDirs`.
- An app with an Android target makes a `BaseDirsFactory` in platform code and passes it to common code. So does an
  app that wants `DirLayout.Xdg` on macOS.
- `forApp` draws a new run id for `temp` on every call. Resolve `AppDirs` once and keep it.
- Nothing is created. `create()` and `forApp()` only compute paths. Android's own directory getters are the
  exception: they create the app's sandbox directories when missing.
- A variable that is blank or not an absolute path counts as unset. It is listed in `ignored`, as `Blank` or
  `NotAbsolute`. An unset variable is not listed. With `strict = true` a listed one is an error instead.
- `runtime` is null unless `$XDG_RUNTIME_DIR` names a private folder on Linux.
- Where a lock goes is in [Locking](../fs/docs/locking.md#reference).

## Tasks

### Find your app's folders

Most apps need one line at startup.

```kotlin
import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.current
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.flatMap

val dirs: Result<AppDirs, DirsError> = BaseDirs.current().flatMap { it.forApp("myapp") }
```

- `BaseDirs.current()` gives what `BaseDirsFactory()` gives on each platform.
- `BaseDirs.current(strict = true)` gives what `BaseDirsFactory(strict = true)` gives.
- It does not exist on Android. So the `commonMain` of a project with an Android target does not see it.

### Make a factory per platform

On Android the factory needs a `Context`, and on macOS you can pick `DirLayout.Xdg`. An app that needs either builds
the factory in platform code and hands it to common code.

```kotlin
import com.fromwau.kern.dirs.*
import com.fromwau.kern.result.*

// androidMain: Android's directories belong to the app, so the factory needs a Context
val factory = BaseDirsFactory(context)

// jvmMain and macosMain: a command-line tool may ask for the XDG layout on macOS
val factory = BaseDirsFactory(DirLayout.Xdg)

// every other target
val factory = BaseDirsFactory()

// JVM, Linux, Windows native, and macOS native under DirLayout.Xdg: a relative XDG_CONFIG_HOME=cfg is an error
val factory = BaseDirsFactory(strict = true)

// jvmMain and macosMain: the XDG layout on macOS, and a relative XDG_CONFIG_HOME=cfg an error
val factory = BaseDirsFactory(DirLayout.Xdg, strict = true)

// common code
val dirs: Result<AppDirs, DirsError> = factory.create().flatMap { it.forApp("myapp") }
```

`BaseDirsFactory` is an `expect class` that declares no constructor. So `BaseDirsFactory()` in `commonMain` does not
compile. Put each line in its target's own source set.

### Report a variable it ignored

To tell the user which variables were ignored, read `ignored`:

```kotlin
factory.create().onSuccess { base ->
    base.ignored.forEach { println("ignored ${it.name}=${it.value}: ${it.reason}") }
}
```

`forApp` copies the list into `AppDirs.ignored`. Code handed only the `AppDirs` can report it too.

### Refuse a relative variable

Pass `strict = true` to make an ignored variable an error.

```kotlin
val base = BaseDirsFactory(strict = true).create().getOrElse { failure ->
    if (failure is DirsError.NotAbsolute) error("${failure.name}=${failure.value} is not an absolute path")
    error("no folders for this app: $failure")
}
```

`strict` also fails on a blank variable, as `Blank`. On Linux it fails on an `XDG_RUNTIME_DIR` that names a missing
or non-private folder, as `NotPrivate`.

An app that never uses `runtime` can skip `XDG_RUNTIME_DIR`. With `toError()` it refuses what `strict` refuses,
except anything about `XDG_RUNTIME_DIR`, whether blank, relative, missing or not private:

```kotlin
import com.fromwau.kern.dirs.BaseDirsFactory
import com.fromwau.kern.dirs.toError
import com.fromwau.kern.result.getOrElse

val base = BaseDirsFactory().create().getOrElse { error("no folders for this app: $it") }
val refused = base.ignored.firstOrNull { it.name != "XDG_RUNTIME_DIR" }?.toError()
if (refused != null) error("no folders for this app: $refused")
```

### Tell the user what to fix

When a root is missing, the error names its kind. Map the kind to advice.

```kotlin
fun fixFor(kind: DirKind): String = when (kind) {
    DirKind.Home -> "set USERPROFILE to your home folder"
    DirKind.Config -> "set APPDATA"
    DirKind.Cache -> "set LOCALAPPDATA"
    DirKind.Temp -> "set TEMP"
}
```

That is the advice for a Windows native app. `UnableToResolve(kind, ignored)` also carries the variables ignored on
the way. So `APPDATA=roaming` on Windows explains a missing `Config`.

### Move a root

```kotlin
val dirs = base.copy(stateHome = override).forApp("myapp")
```

`BaseDirs` holds one root per kind: `home`, `configHome`, `dataHome`, `stateHome`, `cacheHome`, `tempHome` and
`runtimeHome`. On Windows, state and cache share `%LOCALAPPDATA%`. Move both to keep them together.

### Use scratch folders in a test

A test should not touch your real folders. Point every root at a scratch folder, or build `AppDirs` by hand.

```kotlin
import kotlinx.io.files.Path

val dirs: AppDirs = BaseDirs.under(scratch).forApp("myapp").getOrElse { error("not one folder name: $it") }

val fixed = AppDirs(
    config = Path(scratch, "config"),
    data = Path(scratch, "data"),
    state = Path(scratch, "state"),
    cache = Path(scratch, "cache"),
    temp = Path(scratch, "tmp", "run"),
)
```

`BaseDirs.under(scratch)` puts every root in a folder of its own below `scratch`: `home`, `config`, `data`, `state`,
`cache`, `tmp`, and `run` as `runtimeHome`. None still points at a real folder. It creates nothing.

### Test your app's startup

To test how your app reacts to the environment, pass the variables yourself.

```kotlin
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupTest {
    @Test
    fun `a relative XDG_CONFIG_HOME is reported and the config stays under HOME`() {
        val env = mapOf("HOME" to "/home/test", "XDG_CONFIG_HOME" to "cfg")

        val dirs = BaseDirs.fromXdg(env, accountHome = null)
            .flatMap { it.forApp("myapp") }
            .getOrElse { error("no folders for myapp: $it") }

        assertEquals(Path("/home/test/.config/myapp"), dirs.config)
        assertEquals(listOf("XDG_CONFIG_HOME"), dirs.ignored.map { it.name })
    }
}
```

- `BaseDirs.fromXdg(env, accountHome, strict)` resolves the roots the Linux factory gives. It reads the variables
  you pass instead of this process's, on every target.
- `strict = true` refuses what a strict factory refuses.
- `accountHome` stands in for the account's home. It is taken when `HOME` is unset, blank or relative.
- macOS under `DirLayout.Xdg` takes the same home and XDG roots. It keeps the system's temp folder and has no
  `runtime`.

#### Sharp edges

- The example assumes a POSIX host. On a JVM on Windows `/home/test` is not absolute. So `HOME` counts as unset and,
  with no `accountHome`, there is no home.
- `fromXdg` reads the file system only to check that the folder `XDG_RUNTIME_DIR` names is private. A test that sets
  no `XDG_RUNTIME_DIR` touches nothing.
- On the JVM on macOS and on Windows, that folder counts as not private.

## Reference

### The folders

```kotlin
data class AppDirs(
    val config: Path,
    val data: Path,
    val state: Path,
    val cache: Path,
    val temp: Path,            // this run's own scratch folder
    val runtime: Path? = null, // Linux with $XDG_RUNTIME_DIR only
    val ignored: List<IgnoredVariable> = emptyList(), // BaseDirs.ignored, carried over by forApp
)
```

For an app named `myapp`:

| Platform | config | data | state | cache | temp |
|---|---|---|---|---|---|
| Linux | `$XDG_CONFIG_HOME/myapp`, else `~/.config/myapp` | `$XDG_DATA_HOME/myapp`, else `~/.local/share/myapp` | `$XDG_STATE_HOME/myapp`, else `~/.local/state/myapp` | `$XDG_CACHE_HOME/myapp`, else `~/.cache/myapp` | `$TMPDIR/myapp/<run id>`, else `/tmp/myapp/<run id>` |
| macOS | `~/Library/Application Support/myapp` | same as config | same as config | `~/Library/Caches/myapp` | `<system temp>/myapp/<run id>` |
| Windows | `%APPDATA%\myapp` | same as config | `%LOCALAPPDATA%\myapp` | `%LOCALAPPDATA%\myapp\cache` | `%TEMP%\myapp\<run id>` |
| Android | `filesDir/myapp` | same as config | `noBackupFilesDir/myapp` | `cacheDir/myapp` | `cacheDir/tmp/myapp/<run id>` |
| iOS | `Library/Application Support/myapp` in the sandbox | same as config | same as config | `Library/Caches/myapp` in the sandbox | `tmp/myapp/<run id>` in the sandbox |

- **XDG on macOS.** `BaseDirsFactory(DirLayout.Xdg)` on the JVM or macOS native gives macOS the Linux row's config,
  data, state and cache. A command-line tool's files then sit in `~/.config`, as gh's and Neovim's do. Temp stays
  the system's, and `runtime` stays null. Windows, Android and iOS have one convention each and take no layout.
- **Roaming on Windows.** Config and data sit in the roaming `%APPDATA%`. A roaming profile carries it to every
  machine. State and cache describe one machine, so they stay in `%LOCALAPPDATA%`.
- **Home.** `AppDirs` has no `home`. Keep the `BaseDirs` for `path.expandTilde(base.home)`.
- `BaseDirs.home` is `$HOME` on Linux. Otherwise it is the account's home from the account database, which the JVM
  reads as `user.home`.
- On macOS home is the account's home folder. In a sandboxed native app, home, config and cache sit in the app's
  container instead. Home is the user profile on Windows, `filesDir` on Android and the app's sandbox on iOS.
- **Temp is per run.** `forApp` ends `temp` in a random run id, so two runs never share it. `forApp(name, runId: Uuid)`
  and `forApp(id: AppId, runId: Uuid)` take a fixed `kotlin.uuid.Uuid` instead.
- To claim and clear a run's temp, use [`fs`](../fs/docs/run-temp.md)'s `withRunTemp` and `clearStaleTempRuns`.

#### Sharp edges

- **A shared temp root.** Without `$TMPDIR`, Linux and the JVM on Linux and macOS fall back to `/tmp`. There,
  whoever runs an app first owns `/tmp/<app>`, and another user cannot create run folders under it. Set `$TMPDIR`, or
  move the root with `base.copy(tempHome = ...)`. Windows takes `%TEMP%`.
- An account home that is not an absolute path is `UnableToResolve(Home)`. One example is the `?` the JDK reports for
  an account it cannot find.

### Runtime

`runtime` is the folder for sockets and other per-session files. It is a `Path?`: only Linux gives one, and only
when `$XDG_RUNTIME_DIR` names a folder this user owns and nobody else may enter.

- `runtime` is `$XDG_RUNTIME_DIR/myapp` on Linux. Only this user may enter it, and it is emptied at logout.
- The [XDG Base Directory spec](https://specifications.freedesktop.org/basedir/latest/) requires that "the directory
  MUST be owned by the user, and they MUST be the only one having read and write access to it. Its Unix access mode
  MUST be 0700."
- The factory checks what a folder shows when it runs, not later. It takes `$XDG_RUNTIME_DIR` only when it names an
  existing folder that this user owns and whose group and others have no permission bits. A link is followed.
- Otherwise `runtime` is null. The variable is listed in `ignored` as `Blank` when blank, `NotAbsolute` when
  relative, else `NotPrivateFolder`.
- `runtime` is also null when the variable is unset, and on every other platform. A fallback of your own has none of
  these guarantees.

### Environment variables

These are the variables the factories read. A platform named in the table means both its native target and the JVM
on it, unless the row says which. Android and iOS read none. What Windows native reads has not been verified on
Windows.

| variable | read on | unset | blank | relative | not a private folder | under `strict = true` |
|---|---|---|---|---|---|---|
| `HOME` | Linux; macOS under `DirLayout.Xdg`; Windows native when `USERPROFILE` gives no home | the account's home, else `UnableToResolve(Home)` | as unset, and listed in `ignored` as `Blank` | as unset, and listed in `ignored` as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `USERPROFILE` | Windows native | `HOME` is read instead | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `TMPDIR` | Linux; the JVM on macOS | `/tmp` | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `XDG_CONFIG_HOME`, `XDG_DATA_HOME`, `XDG_STATE_HOME`, `XDG_CACHE_HOME` | Linux; macOS under `DirLayout.Xdg` | `~/.config`, `~/.local/share`, `~/.local/state`, `~/.cache` | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `XDG_RUNTIME_DIR` | Linux | `runtime` is null | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | `runtime` is null, and listed as `NotPrivateFolder` | blank: `Blank`; relative: `NotAbsolute`; missing or not private: `NotPrivate` |
| `APPDATA` | Windows | `UnableToResolve(Config)` | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `LOCALAPPDATA` | Windows | `UnableToResolve(Cache)` | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |
| `TEMP` | Windows | `UnableToResolve(Temp)` | as unset, and listed as `Blank` | as unset, and listed as `NotAbsolute` | does not apply | blank: `Blank`; relative: `NotAbsolute` |

- On Windows native, when `USERPROFILE` gives no home, an unset `HOME` is `UnableToResolve(Home)`: there is no
  account-home fallback.
- Trailing separators are dropped, so `HOME=/home/me/` gives the same roots as `/home/me`.
- `BaseDirs.ignored` holds each listed variable as an `IgnoredVariable(name, value, reason)`, in the order read.
- On Linux the variables are read in this order: `HOME`, `TMPDIR`, `XDG_CONFIG_HOME`, `XDG_DATA_HOME`,
  `XDG_STATE_HOME`, `XDG_CACHE_HOME`, `XDG_RUNTIME_DIR`.
- `strict = true` is taken by the JVM, Linux, macOS and Windows native factories. The macOS native one reads
  variables only under `DirLayout.Xdg`.
- Under `strict`, the first variable that would be listed in `ignored` is the error. That is `Blank(name, value)` or
  `NotAbsolute(name, value)`, also when it is why a root is missing. For `XDG_RUNTIME_DIR` it can also be
  `NotPrivate(name, value)`.

#### Details

- The JVM on Windows takes home from `user.home` alone.
- macOS and iOS native ask Foundation for the system temp folder. Inside a sandbox that is the container's.
- A `HOME` that is unset, blank or relative is replaced by the account's home. With no account home, nothing after
  `HOME` is read.
- So under `strict` a blank or relative `HOME` is `Blank("HOME", value)` or `NotAbsolute("HOME", value)`. An unset
  one with no account home is `UnableToResolve(Home)`, even when a later variable is relative.

### App names

`forApp` needs a folder name for your app. Pass one name to use it everywhere, or pass an `AppId` to let each
platform name the folder its own way.

`forApp(AppId(qualifier, organization, application))` names the app's folder the way each platform does.
`AppId("com", "Foo Corp", "Bar App")` is `barapp` on Linux and Android, `com.Foo-Corp.Bar-App` on macOS and iOS, and
`Foo Corp\Bar App` on Windows.

- Linux, Android and macOS under `DirLayout.Xdg` use the application alone. It is trimmed, each run of whitespace is
  removed and every letter is lowercased. Everything else stays, so `Bar-App` is `bar-app`.
- macOS and iOS join qualifier, organization and application with `.`. First each space in the organization and the
  application turns into `-`. Case and every other character stay. An empty part is left out.
- Windows nests the application's folder inside the organization's, both as given. An empty organization is left
  out.
- A folder that comes out blank, `.`, `..`, holding `/`, `\` or a NUL, or longer than 255 bytes in UTF-8 is
  `InvalidAppName`. An `AppId` whose application, as given, is any of these is `InvalidAppName` too, even where
  naming would trim or drop it.

### Building roots yourself

You can also build the roots without a factory.

- `BaseDirs.under(scratch)` takes a `naming` as its second argument. It is `AppNaming.Xdg` unless you pass another.
- `BaseDirs` also takes the roots by name. `runtimeHome` and `ignored` are optional, and `naming` defaults to
  `AppNaming.Xdg` on every platform. `forApp` works on either as on a factory's.
- A test that moves every root into a scratch folder must move `runtimeHome` too, since it comes from
  `$XDG_RUNTIME_DIR`.
- On Windows, moving state or cache alone takes cache out of the state folder into its own.
- A test that needs no roots at all builds `AppDirs` directly. `runtime` and `ignored` are optional.

### Errors

| `DirsError` | meaning |
|---|---|
| `UnableToResolve(kind, ignored)` | a variable or OS answer the root needs is missing |
| `InvalidAppName(name)` | the name given to `forApp`, or a folder an `AppId` makes, is not one folder name |
| `NotAbsolute(name, value)` | a factory made with `strict = true` read the environment variable `name` as `value`, which is not an absolute path |
| `NotPrivate(name, value)` | a factory made with `strict = true` read `XDG_RUNTIME_DIR` as `value`, which is not a private folder |
| `Blank(name, value)` | a factory made with `strict = true` read the environment variable `name` as `value`, which is empty or only whitespace |

- In `UnableToResolve`, `kind` is a `DirKind`: `Home`, `Config`, `Cache` or `Temp`. `ignored` lists the blank or
  relative variables read on the way, as `BaseDirs.ignored` does.
- `InvalidAppName` means blank, `.`, `..`, holding `/`, `\` or a NUL, or longer than 255 bytes in UTF-8.
- `NotPrivate` means the value names no existing folder this user owns with no group or other permission bits.
- To use `DirsError` beside your own errors in one `Result`, your errors implement `ResultError` too. See
  [`result`'s README](../result/README.md#declaring-your-errors).

#### Details

- `DirKind` has no `Data` or `State`. Each comes from a root that is looked up, so its failure is that root's:
  - On Linux both fall back to a folder under `Home`.
  - On macOS and iOS both share `Config`'s folder.
  - On Windows data shares `Config`'s `%APPDATA%`, and state is `%LOCALAPPDATA%`, the `Cache` root.
  - Android never fails.
- The JVM on Windows can fail with all four kinds. On Linux only `Home` can.
- Where `Home` comes from:
  - On Linux and under `DirLayout.Xdg`, from `HOME`, else the account's home (`user.home` on the JVM).
  - On Windows native, from `USERPROFILE`, else `HOME`.
  - On the JVM on Windows and macOS's native layout, from the account's home alone.
- On macOS and iOS native, `Config` and `Cache` mean the system named no Application Support or Caches folder. No
  variable fixes that.

## License

[Apache-2.0](../LICENSE).
