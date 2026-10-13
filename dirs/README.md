# dirs

Where an app's config, data, state, cache and temp files belong on every Kotlin Multiplatform target. It only
computes the directories' paths; building file paths under them, and reading, writing or locking files, is
[`fs`](../fs/README.md).

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

KMP consumers put it in `commonMain`. kern's `result` and kotlinx-io come along as `api`, since `Result`,
`IError` and `Path` appear in this module's signatures. `forApp` takes a `kotlin.uuid.Uuid`, so consumers need
Kotlin 2.4 or newer. The JVM and Android artifacts are Java 17 bytecode, so those two need JDK 17 or newer,
and Android needs `minSdk` 26 and `compileSdk` 37; the native targets have no such requirement.

## Where files go

Only platform code constructs a factory, since Android's takes a `Context`: `BaseDirsFactory` is an
`expect class` declaring no constructor, so `BaseDirsFactory()` in `commonMain` does not compile. Put the line
that matches each target in that target's own source set and pass the factory to the common code, which holds
and calls it like any other value.

```kotlin
// androidMain: Android's directories belong to the app, so the factory needs a Context
val factory = BaseDirsFactory(context)

// jvmMain and macosMain: a command-line tool may ask for the XDG layout on macOS
val factory = BaseDirsFactory(DirLayout.Xdg)

// every other target
val factory = BaseDirsFactory()

// common code
val dirs: Result<AppDirs, DirsError> = factory.create().flatMap { it.forApp("myapp") }
```

For an app named `myapp`:

| Platform | config | data | state | cache | temp |
|---|---|---|---|---|---|
| Linux | `$XDG_CONFIG_HOME/myapp`, else `~/.config/myapp` | `$XDG_DATA_HOME/myapp`, else `~/.local/share/myapp` | `$XDG_STATE_HOME/myapp`, else `~/.local/state/myapp` | `$XDG_CACHE_HOME/myapp`, else `~/.cache/myapp` | `$TMPDIR/myapp/<run id>`, else `/tmp/myapp/<run id>` |
| macOS | `~/Library/Application Support/myapp` | same as config | same as config | `~/Library/Caches/myapp` | `<system temp>/myapp/<run id>` |
| Windows | `%APPDATA%\myapp` | same as config | `%LOCALAPPDATA%\myapp` | `%LOCALAPPDATA%\myapp\cache` | `%TEMP%\myapp\<run id>` |
| Android | `filesDir/myapp` | same as config | `noBackupFilesDir/myapp` | `cacheDir/myapp` | `cacheDir/tmp/myapp/<run id>` |
| iOS | `Library/Application Support/myapp` in the sandbox | same as config | same as config | `Library/Caches/myapp` in the sandbox | `tmp/myapp/<run id>` in the sandbox |

- **The XDG layout on macOS.** `BaseDirsFactory(DirLayout.Xdg)` on the JVM or macOS native gives macOS the Linux
  row's config, data, state and cache, so a command-line tool's files sit in `~/.config` with the user's other
  dotfiles, as gh's and Neovim's do. Temp stays the system's, and `runtime` stays null. Linux already
  uses XDG, and Windows, Android and iOS have one convention each and take no layout.
- **Home.** `BaseDirs.home` is `$HOME` on Linux, where the JVM falls back to `user.home` when it is unset. A
  `user.home` that is not an absolute path, such as the `?` the JDK reports for an account it cannot find,
  counts as unset too. On macOS it is the account's home folder, except in a sandboxed native app, where home,
  config and cache all sit in the app's container. It is the user profile on Windows, `filesDir` on Android
  and the app's sandbox on iOS.
- **Roaming on Windows.** Config and data sit in the roaming `%APPDATA%`, so a roaming profile carries them to
  every machine the user signs in to. State and cache describe one machine and stay in `%LOCALAPPDATA%`.
- **Environment variables.** A blank variable counts as unset. `$TMPDIR` sets the temp root on Linux and on
  the JVM, falling back to `/tmp`; macOS and iOS native ask Foundation for the system temp folder, which is the
  container's inside a sandbox.
- **Nothing is created.** `create()` and `forApp()` only compute paths. The one exception is Android, where the
  Context's own directory getters create the app's sandbox directories when they are missing.
- **Temp is per run.** `forApp` ends `temp` in a random run id, so two runs of one app never share it, and
  `forApp("myapp", runId)` takes a fixed id instead. Resolve `AppDirs` once per run. Creating, claiming and
  clearing the folder is [`fs`](../fs/README.md)'s job: `withRunTemp`, `staleTempRuns` and `deleteRecursively`.
- **Runtime.** `runtime` is `$XDG_RUNTIME_DIR/myapp` on Linux, for sockets, PID files and locks: only this user
  may enter it, and it is emptied at logout. It is null when the variable is unset and on every other platform,
  since none has a folder with those guarantees; pick your own fallback there, knowing it has none of them.
- **A full app identity.** `forApp(AppId("com", "Foo Corp", "Bar App"))` names the app's folder the way each
  platform does: `barapp` on Linux and Android, `com.Foo-Corp.Bar-App` on macOS and iOS, and `Foo Corp\Bar App`
  on Windows. Under `DirLayout.Xdg`, macOS uses the Linux name. `forApp("barapp")` uses one name everywhere.
- **Moving a root.** Copy the value: `base.copy(stateHome = override).forApp("myapp")`. On Windows, state and
  cache share `%LOCALAPPDATA%`, so moving either one takes cache out of the state folder into its own; move both
  to keep them together.

| `DirsError` | meaning |
|---|---|
| `UnableToResolve(kind)` | a variable or OS answer the root needs is missing: `Home`, `Config`, `Cache` or `Temp` |
| `InvalidAppName(name)` | the name given to `forApp`, or a folder an `AppId` makes, is not one folder name: blank, `.`, `..`, or holding `/` or `\` |

## Known limits

- **A shared temp root.** Without `$TMPDIR`, Linux and the JVM fall back to `/tmp`, where whoever runs an app
  first owns `/tmp/<app>`, so another user on the same machine cannot create run folders under it. Set `$TMPDIR`,
  or move the root with `base.copy(tempHome = ...)`.
- **Relative XDG values** are used as given, so they are relative to the working directory.

## License

[Apache-2.0](../LICENSE).
