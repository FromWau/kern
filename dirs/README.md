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

Only platform code constructs a factory, since Android's takes a `Context`: `BaseDirsFactory` is an
`expect class` declaring no constructor, so `BaseDirsFactory()` in `commonMain` does not compile. Put the line
that matches each target in that target's own source set and pass the factory to the common code, which holds
and calls it like any other value.

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

- **Home.** `BaseDirs.home` is `$HOME` on Linux, where the JVM falls back to `user.home` when it is unset. A
  `user.home` that is not an absolute path, such as the `?` the JDK reports for an account it cannot find,
  counts as unset too. On macOS it is the account's home folder, except in a sandboxed native app, where home,
  config and cache all sit in the app's container. It is the user profile on Windows, `filesDir` on Android
  and the app's sandbox on iOS.
- **Environment variables.** A blank variable counts as unset. `$TMPDIR` sets the temp root on Linux and on
  the JVM, falling back to `/tmp`; macOS and iOS native ask Foundation for the system temp folder, which is the
  container's inside a sandbox.
- **Nothing is created.** `create()` and `forApp()` only compute paths. The one exception is Android, where the
  Context's own directory getters create the app's sandbox directories when they are missing.
- **Temp is per run.** `forApp` ends `temp` in a random run id, so two runs of one app never share it, and
  `forApp("myapp", runId)` takes a fixed id instead. Resolve `AppDirs` once per run and clear `temp` with
  `deleteRecursively()` on shutdown. `dirs.withRunTemp { temp -> runApp(temp) }` creates the folder and claims it
  for as long as the block runs, by holding a lock on `.run.lock` inside it. `staleTempRuns()` then lists the
  folders of this app's other runs that no live run holds, which is what a crash or a `kill -9` leaves, so a
  cleanup can tell them from runs still going. That only works when every run claims its folder this way: one
  whose run never did, or is starting at that very moment, counts as stale.
- **Moving a root.** Copy the value: `base.copy(stateHome = override).forApp("myapp")`.

| `DirsError` | meaning |
|---|---|
| `UnableToResolve(kind)` | a variable or OS answer the root needs is missing: `Home`, `Config`, `Cache` or `Temp` |
| `InvalidAppName(name)` | the name given to `forApp` is not one folder name: blank, `.`, `..`, or holding `/` or `\` |

## Path helpers

Nothing here throws. A failure is a `FileError` in the `Result`, and every case names the path it is about.

```kotlin
val file = dirs.config / "app.toml"

file.readText(maxBytes = 1_048_576)   // Result<String, FileError>
file.writeText("theme = \"dark\"")     // EmptyResult<FileError>
dirs.state.createDirectories()        // EmptyResult<FileError>
```

- **Names.** `dir / "name"` appends what it is given as written: a separator in it adds more than one segment,
  `""` adds none, and `".."` stays a segment, so its `parent` is `dir` rather than the folder above. `extension` is
  everything after the last dot of the file name, so `archive.tar.gz` gives `gz`; a leading dot marks a hidden file,
  so `.bashrc` has none. `nameWithoutExtension` is the rest. `expandTilde(home)` expands `~` and `~/...` and leaves
  `~bob/...` alone, so the result can still be relative. An empty path names nothing, and every call answers it as
  `NotFound`.
- **Reading.** `exists()` follows symlinks, and answers `false` for a path that cannot be looked up as well as
  for one that is not there. A read that returns a `Result` keeps those apart: `Inaccessible` for a path it may
  not look at, `NotFound` for one that is not there. `fileType()` says what is at a path, and `fileSize()` how
  many bytes a regular file holds; anything else is `NotRegularFile`.
  `readText(maxBytes)` refuses a directory, FIFO, device or socket before opening it, since opening a FIFO
  blocks until something writes to it. A file longer than `maxBytes` is `TooLarge`, and bytes that are not
  valid UTF-8 read as U+FFFD. `readBytes(maxBytes)` reads the same way and hands back the bytes, for a caller
  that decodes them another way or refuses a file that is not UTF-8.
- **Writing.** `writeText` and `writeBytes` write into the file itself, so its permissions, owner and links stay
  as they are, and a file that cannot be read or written is refused. Anything at the path that is not a regular
  file is `NotRegularFile`, the same answer a read gives: opening a FIFO waits on a reader that may never come,
  and putting a backup back over a device node would replace the node with a regular file. They copy the old
  content to a backup beside the file first and move it back when the write fails. A write that succeeds tries
  to delete the backup, and one that cannot be deleted stays. If undoing a failed write fails too, the result
  is `RestoreFailed`.
- **Leftover backups.** `leftoverBackups()` lists the backups a write left beside a file. A write disposes of
  its own when it can, so one left behind is a reason to check the file rather than proof that it is wrong, and
  neither copy is the one to trust by default. It is the one call here whose error names the folder instead of
  the file, since reading the folder is what can fail.
- **Folders.** Every write creates missing parent folders. `createDirectories()` works like `mkdir -p`, also
  when another process creates the same folders at the same time. A file standing at the path or in place of a
  parent is `NotADirectory` naming that file, and a path it may not look up is `Inaccessible` naming that path,
  rather than a folder it reports as having failed to create.
- **Removing and listing.** `delete()` removes a file, symlink or empty folder, and `deleteRecursively()` clears a
  whole tree. Both take a symlink as a link, so what it points at stays. `deleteRecursively()` stops at the first
  entry it cannot remove and returns that failure, which can leave part of the tree behind. `list()` gives a
  folder's entries as full paths in name order; anything that is not a folder is `NotADirectory`.
- **Moving.** `moveTo(target)` moves a file or folder in one step, replacing a file already at `target` on the
  JVM, Android, Linux and Apple; whether Windows native replaces it has not been verified. Both paths must be on
  one file system, so a move across two is `WriteFailed`: copy and delete instead.
- **Locking.** `lockFile.withLock { work() }` runs the work holding an exclusive lock on `lockFile`, so two runs
  never overlap, whether they are threads of one process or two processes, JVM and native alike. It is `fcntl`
  on Linux and Apple, the primitive the JVM's `FileChannel.lock` also uses, and `LockFileEx` on Windows. Waiting
  is bounded: a holder that keeps it past `waitMillis`, half a second unless you pass another, is `LockBusy`,
  and zero tries once. Lock a file nothing else opens, a sibling `.lock` rather than the file you work on, since
  closing any descriptor to a file drops the process's locks on it. The lock file and its missing folders are
  created and stay; deleting one to tidy up lets a new holder in beside the one still holding it. The lock is
  advisory, so it keeps out other `withLock` calls and nothing else.
- **Walking.** `walkTopDown()` yields every regular file under a folder, and an error for anything it could not
  read. Each folder's own files come first, in name order, then its subfolders, also in name order. Symlinks are
  followed and nothing tracks where the walk has been, so a link pointing back up repeats that folder at every
  level until the OS stops following links (about 40 deep on Linux). A FIFO, device or socket inside a folder is
  skipped, and so is a symlink whose target is missing, since neither is a file to read; named as the folder to
  walk, a FIFO is `NotRegularFile`.

```kotlin
root.walkTopDown().forEach { it.fold(onSuccess = ::index, onError = ::warn) }
```

| `FileError` | meaning |
|---|---|
| `NotFound` | nothing at the path: missing, a symlink whose target is missing, or a path that runs through a file and so cannot resolve |
| `Inaccessible(reason)` | the path could not be read or looked up |
| `NotRegularFile(type)` | a directory, FIFO, device or socket where a file was expected |
| `NotADirectory(type)` | a file, FIFO, device or socket where a folder was expected |
| `TooLarge(limitBytes, atLeastBytes)` | at least `atLeastBytes` bytes, over the limit; a read that crosses the limit stops there instead of measuring the rest |
| `WriteFailed(reason)` | writing the file or creating a folder failed |
| `LockBusy(holderPid, waitedMs)` | another holder kept the lock past the wait; `holderPid` names it on native Linux and Apple and is null elsewhere |
| `LockFailed(reason)` | the lock could not be attempted: the lock file would not open, or the OS refused the call |
| `RestoreFailed(reason, backup)` | a write failed and undoing it failed too, so the file is damaged. `backup` names the copy to put back, and is null only when there is none: the file was new, or the copy is gone |

`reason` is the error message, which differs per platform; on `RestoreFailed` it is the undo's message rather
than the write's, since the undo is what left the file damaged.

## Known limits

- **A full disk has no case of its own.** Every refusal the filesystem makes is one `WriteFailed`, carrying
  the platform's own message. `TooLarge` is typed because it is *this library's* ceiling, so "bigger than the
  cap you set" and "this machine is full" do not read alike, and it is the second that has no type. The JVM
  raises a bare `IOException` for `ENOSPC` with no subclass to match on, and its text comes from `strerror`, so
  a typed case built on that message would be dead on a machine that does not speak English. Read `reason`
  yourself where a full disk has to be told from anything else.
- **What kotlinx-io's `SystemFileSystem` cannot do.**
  - There is no modification time: its file metadata holds only a type and a size.
  - Nothing forces data to disk, so a finished write survives a crashed process but not a power cut.
  - A symlink whose target is missing cannot be removed: kotlinx-io looks a path up before deleting it, so
    `delete()` reports `NotFound` and `deleteRecursively()` stops on the folder that still holds the link.
  - A symlink whose target is missing cannot be resolved either, so a write through one cannot create the
    folders its target needs and fails on the link instead.
- **Writes are not atomic, and only one write may run on a file at a time, whether from other threads or other
  processes.**
  - A process killed mid-write leaves the file half-written, with the backup `.<name>.<id>.bak` beside it, and
    `leftoverBackups()` finds it. The file itself may read perfectly, since a write cut short can leave text
    that still parses and says less than it did, so a leftover is a reason to check both copies rather than
    proof of which one is good: a process that died while the backup was still being copied leaves a backup
    shorter than the file. `<id>` identifies the write that made it, so a copy kept beside the file under a
    name of your own, such as `.app.toml.before-my-edit.bak`, is never counted as one.
  - A write that creates the file has nothing to back up, so a process killed during it leaves the new file
    half-written with no backup, and `leftoverBackups()` has nothing to find.
  - Readers can see the file half-written while the write runs.
  - Two writes at once can leave a mix of both, and a failed write can undo another one that finished.
- **The backup sits beside the file.** It needs write permission on the folder, not only on the file. Its name is
  42 bytes longer than the file's, so where names stop at 255 bytes a file named with more than 213 can be created,
  since a new file needs no backup, and every later write to it fails with `WriteFailed`. Where a platform has
  permissions it carries the file's own, before any content goes into it, so a config only you may read is never
  copied into one anybody may, and a restore leaves the file holding them. What a restore does not keep is the rest
  of the file's identity: the copy is a new file, so it has its own timestamps and inode, and other hard links to
  the original keep the half-written content. Windows has no such permissions, and what may read a file there comes
  from the folder it sits in: the native target copies the read-only flag, which is all a mode says there, and the
  JVM one leaves the copy to the folder.
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
