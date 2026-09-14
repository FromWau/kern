# dirs

Where an app's config, data, state, cache and temp files belong on every Kotlin Multiplatform target, and
`Path` helpers that return typed errors instead of throwing.

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
Kotlin 2.4 or newer. The JVM and Android artifacts are Java 25 bytecode, so those two need a JDK 25 toolchain,
and Android needs `minSdk` 26 and `compileSdk` 37; the native targets have no such requirement.

## Where files go

```kotlin
// androidMain: Android's directories belong to the app, so the factory needs a Context
val factory = BaseDirsFactory(context)

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
| Windows | `%APPDATA%\myapp` | same as config | same as config | `%LOCALAPPDATA%\myapp` | `%TEMP%\myapp\<run id>` |
| Android | `filesDir/myapp` | same as config | `noBackupFilesDir/myapp` | `cacheDir/myapp` | `cacheDir/tmp/myapp/<run id>` |
| iOS | `Library/Application Support/myapp` in the sandbox | same as config | same as config | `Library/Caches/myapp` in the sandbox | `tmp/myapp/<run id>` in the sandbox |

- **Home.** `BaseDirs.home` is `$HOME` on Linux, where the JVM falls back to `user.home` when it is unset. On
  macOS it is the account's home folder, except in a sandboxed native app, where home, config and cache all sit
  in the app's container. It is the user profile on Windows, `filesDir` on Android and the app's sandbox on iOS.
- **Environment variables.** A blank variable counts as unset. `$TMPDIR` sets the temp root on Linux and on
  the JVM, falling back to `/tmp`; macOS and iOS native ask Foundation for the system temp folder, which is the
  container's inside a sandbox.
- **Nothing is created.** `create()` and `forApp()` only compute paths. The one exception is Android, where the
  Context's own directory getters create the app's sandbox directories when they are missing.
- **Temp is per run.** `forApp` ends `temp` in a random run id, so two runs of one app never share it, and
  `forApp("myapp", runId)` takes a fixed id instead. Resolve `AppDirs` once per run, create `temp` when you need
  it, and clear it with `deleteRecursively()` on shutdown.
- **Moving a root.** Copy the value: `base.copy(stateHome = override).forApp("myapp")`.

| `DirsError` | meaning |
|---|---|
| `UnableToResolve(kind)` | a variable or OS answer the root needs is missing: `Home`, `Config`, `Cache` or `Temp` |
| `InvalidAppName(name)` | the name given to `forApp` is blank |

## Path helpers

Nothing here throws. A failure is a `FileError` in the `Result`, and every case names the path it is about.

```kotlin
val file = dirs.config / "app.toml"

file.readText(maxBytes = 1_048_576)   // Result<String, FileError>
file.writeText("theme = \"dark\"")     // EmptyResult<FileError>
dirs.state.createDirectories()        // EmptyResult<FileError>
```

- **Names.** `dir / "name"` appends a segment. `extension` is everything after the last dot of the file name,
  so `archive.tar.gz` gives `gz`; a leading dot marks a hidden file, so `.bashrc` has none.
  `nameWithoutExtension` is the rest. `expandTilde(home)` expands `~` and `~/...` and leaves `~bob/...` alone.
- **Reading.** `exists()` follows symlinks and also answers `false` when the path cannot be looked up.
  `readText(maxBytes)` refuses a directory, FIFO, device or socket before opening it, since opening a FIFO
  blocks until something writes to it. A file longer than `maxBytes` is `TooLarge`, and bytes that are not
  valid UTF-8 read as U+FFFD.
- **Writing.** `writeText` and `writeBytes` write into the file itself, so its permissions, owner and links stay
  as they are, and a file that cannot be read or written is refused. They copy the old content to a backup beside
  the file first and move it back when the write fails. A write that succeeds tries to delete the backup, and one
  that cannot be deleted stays. If undoing a failed write fails too, the result is `RestoreFailed`. A directory
  at the path is `NotRegularFile`.
- **Folders.** Every write creates missing parent folders. `createDirectories()` works like `mkdir -p`, also
  when another process creates the same folders at the same time.
- **Removing and listing.** `delete()` removes a file, symlink or empty folder, and `deleteRecursively()`
  clears a whole tree. Both take a symlink as a link, so what it points at stays. `list()` gives a folder's
  entries as full paths in name order; anything that is not a folder is `NotADirectory`.
- **Walking.** `walkTopDown()` yields every regular file under a folder, and an error for anything it could not
  read. Each folder's own files come first, in name order, then its subfolders, also in name order. Symlinks are
  followed and nothing tracks where the walk has been, so a link pointing back up repeats that folder at every
  level until the OS stops following links (about 40 deep on Linux).

```kotlin
root.walkTopDown().forEach { it.fold(onSuccess = ::index, onError = ::warn) }
```

| `FileError` | meaning |
|---|---|
| `NotFound` | nothing at the path, or a symlink whose target is missing |
| `Inaccessible(reason)` | the path could not be read or looked up |
| `NotRegularFile(type)` | a directory, FIFO, device or socket where a file was expected |
| `NotADirectory(type)` | a file, FIFO, device or socket where a folder was expected |
| `TooLarge(limitBytes, atLeastBytes)` | at least `atLeastBytes` bytes, over the limit; a read that crosses the limit stops there instead of measuring the rest |
| `WriteFailed(reason)` | writing the file or creating a folder failed |
| `RestoreFailed(reason, backup)` | a write failed and could not be undone: the file is damaged, and `backup` holds the old content (null when the file was new) |

`reason` is the error message, which differs per platform.

## Known limits

- **What kotlinx-io's `SystemFileSystem` cannot do.**
  - There is no modification time: its file metadata holds only a type and a size.
  - Nothing forces data to disk, so a finished write survives a crashed process but not a power cut.
  - A symlink whose target is missing cannot be removed: kotlinx-io looks a path up before deleting it, so
    `delete()` reports `NotFound` and `deleteRecursively()` stops on the folder that still holds the link.
  - On the JVM and Apple targets, a path that cannot be looked up reads as `NotFound`. On the JVM, a folder that
    may not be read also lists as empty.
- **Writes are not atomic, and only one write may run on a file at a time, whether from other threads or other
  processes.**
  - A process killed mid-write leaves the file half-written, with the backup `.<name>.<id>.bak` beside it. That
    backup may be the only intact copy of the old content.
  - Readers can see the file half-written while the write runs.
  - Two writes at once can leave a mix of both, and a failed write can undo another one that finished.
  - A FIFO or device at the path is written into, as a shell redirect does, and opening a FIFO waits for a
    reader. On Linux native, and likely Apple native, a reader that leaves early ends the process with SIGPIPE.
- **The backup sits beside the file.** It needs write permission on the folder, not only on the file, and the 42
  characters it adds to the name make a file name over about 213 bytes fail. kotlinx-io cannot set permissions,
  so the backup has default ones while it exists. After a restore, the file has the backup's default metadata,
  and other hard links to it keep the half-written content.
- **Windows native.** kotlinx-io cannot follow symlinks there, so a restore replaces a link with a regular file,
  while the link's real target keeps the half-written content. Undoing a failed first write through a link whose
  target is missing can remove the link and leave the half-written file at its target. Its file calls use the ANSI
  Windows APIs, so a path with non-ASCII characters, such as a user name like `Jürgen`, likely fails. The JVM on
  Windows is unaffected.
- **A shared temp root.** Without `$TMPDIR`, Linux and the JVM fall back to `/tmp`, where whoever runs an app
  first owns `/tmp/<app>`, so another user on the same machine cannot create run folders under it. Set `$TMPDIR`,
  or move the root with `base.copy(tempHome = ...)`.
- **Relative XDG values** are used as given, so they are relative to the working directory.

## License

[Apache-2.0](../LICENSE).
