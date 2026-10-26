# Folders and moves

Building a folder, moving something into place and switching a link each happen in one step, so a reader never sees a
folder half built or a link missing.

These calls follow every link on the way. To keep any of it inside one of your app's folders, go through a
[Writer](writer.md).

Part of [`fs`](../README.md).

```kotlin
fun Path.createDirectories(ownerOnly: Boolean = false): EmptyResult<FileError>
inline fun <T> Path.createDirectoryAtomically(build: (stage: Path) -> T): Result<T, FileError>
fun Path.delete(): EmptyResult<FileError>
fun Path.deleteRecursively(): EmptyResult<FileError>
fun Path.moveTo(target: Path): EmptyResult<FileError>
fun Path.createSymlink(target: Path): EmptyResult<FileError>
fun Path.switchSymlinkTo(target: Path): EmptyResult<FileError>
fun Path.readSymlink(): Result<Path, FileError>
fun Path.list(): Result<List<Path>, FileError>
```

## Rules

- `createDirectories()` works like `mkdir -p`. It also works when another process creates the same folders at the
  same time.
- Every write creates its missing folders with the permissions the umask gives.
- `ownerOnly = true` makes each level it creates `700` from the moment it exists. It leaves a level already there as
  it is.
- `createDirectoryAtomically { stage -> }` fills a stage beside the folder. Once the block returns, it renames the
  stage into place, so nobody sees it half built. Anything already at the path is `AlreadyExists`.
- A block that throws, returns out, or answers an `Err` places nothing. The stage is removed.
- `moveTo(target)` moves a file or folder in one step. It makes `target`'s missing folders first.
- Both paths of a move must be on one file system. A move across two is `WriteFailed`, so use `copyTo` and `delete`
  instead.
- `delete()` removes a file, symlink or empty folder. `deleteRecursively()` clears a tree like `rm -rf`.
- Both remove a link as a link, never what it points at.
- `list()` gives a folder's entries as full paths in name order.
- `createSymlink(target)` never replaces anything.
- `switchSymlinkTo(target)` replaces a link in one step, so a reader sees the old target or the new, and never no
  link.

## Tasks

### Create folders

```kotlin
dirs.state.createDirectories(ownerOnly = true).onError { return report(it) }
```

A level already there keeps its mode, so check one that must stay private yourself, or let
[`privateWriter()`](writer.md#keep-a-secret) refuse it.

### Build a folder whole

Use this when a folder is only useful complete, such as an installed package. Readers see no folder, then the whole
one.

```kotlin
/** Installs [files], each a name inside the package and its content, as one version, whole or not at all. */
fun AppDirs.install(
    name: String,
    version: String,
    files: Map<String, ByteArray>,
): EmptyResult<FileError> = result {
    val packages = cache.writer().bind()
    packages.createDirectoryAtomically("$name/$version") { stage ->
        // A name such as ../evil is InvalidRelativePath, written nowhere, and the stage then goes.
        for ((file, bytes) in files) stage.writeBytes(file, bytes).bind()
    }.bind()
}
```

`Writer.createDirectoryAtomically` hands the block a writer on the stage. The stage is that writer's boundary too.

A link the build makes or follows must stay inside the stage. `stage.switchSymlink("up", Path(".."))` is `Escapes`,
and a write through such a link is `Escapes` with nothing written. A link to a folder inside the stage works.

### Leave out a name a package may not make

```kotlin
fun AppDirs.installLeavingOut(
    name: String,
    version: String,
    files: Map<String, ByteArray>,
): EmptyResult<FileError> = result {
    val packages = cache.writer().bind()
    packages.createDirectoryAtomically("$name/$version") { stage ->
        for ((file, bytes) in files) {
            stage.writeBytes(file, bytes).onError { failure ->
                if (failure is FileError.NameRefused) {
                    println("left out $file: $failure")
                    continue
                }
            }.bind()
        }
    }.bind()
}
```

- `NameRefused` is every name a `Writer` will not make. Nothing is written for it. It covers:
  - `InvalidRelativePath` for `../evil` or `/abs/path`.
  - `Escapes` for a name a link takes out of the stage.
  - `InvalidName` and `NameTooLong`.
- Any other failure still ends the build, and the stage goes, as in [Build a folder whole](#build-a-folder-whole).

### Name a file that failed in the build

```kotlin
fun AppDirs.installHooks(hooks: Map<String, String>): EmptyResult<FileError> = result {
    val tools = data.writer().bind()
    tools.createDirectoryAtomically("tool") { stage ->
        for ((name, script) in hooks) {
            stage.writeText("hooks/$name", script, executable = true)
                .onError { println("hooks/$name not written: $it") }
                .bind()
        }
    }.bind()
}
```

An error from the stage's writer names each path as it will be once placed, such as `tool/hooks/sync.sh` below
`data`. That is because the stage is gone once the build returns.

The stage path shows only in a `FailedAt.Temp`, where the error has an `at`. The stage writer's `root` answers the
real stage. For a shorter message, print the name you gave the stage's writer, as the block does.

### Make a second install wait for the first

```kotlin
suspend fun AppDirs.installLocked(
    name: String,
    version: String,
    files: Map<String, ByteArray>,
): EmptyResult<FileError> = result {
    val locks = (cache / "locks").writer().bind()
    val packages = (cache / "packages").writer().bind()
    locks.awaitLock("$name/$version.lock", wait = 30.seconds) {
        packages.createDirectoryAtomically("$name/$version") { stage ->
            for ((file, bytes) in files) stage.writeBytes(file, bytes).bind()
        }.orElse { failure ->
            val installed = failure is FileError.AlreadyExists && failure.type == FileType.Directory
            if (installed) Ok(Unit) else Err(failure)
        }.bind()
    }.bind()
}
```

- Each version has a lock of its own, so two versions of one package install at once.
- A second install of one version waits for the first. It then finds the version in place as `AlreadyExists` of a
  `Directory`, and answers that as installed.
- A whole folder there is trusted, since only an install puts one at that place.
- A file or a link there is `AlreadyExists` of its own type, and that error is the answer.
- The locks have a folder of their own, so no package name collides with a lock file, and `packages.list()` shows
  only packages.
- `awaitLock` comes from [`fs-coroutines`](../../fs-coroutines/README.md). A `bind()` inside its block releases the
  lock and ends the `result` block with that error.

### Switch the version in use

```kotlin
suspend fun AppDirs.useVersion(
    name: String,
    version: String,
): EmptyResult<FileError> = result {
    val locks = (cache / "locks").writer().bind()
    val packages = (cache / "packages").writer().bind()
    locks.awaitLock("$name/current.lock", wait = 30.seconds) {
        packages.switchSymlink("$name/current", Path(version)).bind()
    }.bind()
}
```

A reader of `current` sees the old version or the new, never no link.

The [package cache scan](leftovers.md#scan-a-package-cache) discards a switch cut short, under the same lock, so it
never takes the staged link of a switch still running.

### Make and read a symlink

```kotlin
import kotlinx.io.files.Path

(dirs.data / "current").createSymlink(Path("release-42")).onError { return report(it) }
val target = (dirs.data / "current").readSymlink().getOrElse { return report(it) }   // Path("release-42")
```

### Point a link at a new release in one step

```kotlin
val data = dirs.data.writer().getOrElse { return report(it) }
data.switchSymlink("current", Path("release-43")).onError { return report(it) }
```

`Writer.switchSymlink` switches a link below a writer's root as `switchSymlinkTo` does. It refuses a folder on the
way that a link puts outside the root, as `Escapes`.

It judges `target` by its names alone. A `target` that is itself a link leading out is switched to, and reads through
the new link answer `Escapes`.

## Reference

You do not need this part on a first read. Come back when a folder, move or link does something you did not expect.

**Creating folders:**

- A folder a write cannot make is `WriteFailed` naming the file the write was given. Its `at` is that folder, as a
  `FailedAt.Folder`.
- Any other failure on the way, such as `NotADirectory` or `Inaccessible`, is what `createDirectories()` answers.
- A file at the path, or in place of a parent, is `NotADirectory` naming that file.
- A path it may not look up is `Inaccessible` naming that path.
- A dangling symlink at the path, or in place of a parent, is `WriteFailed` naming the link. Its target is not
  created.
- Every missing level is made as written, as `mkdir -p` makes it, `.` and `..` included.
- So `(dir / "a" / "ok" / "..").createDirectories()` makes `dir/a` and `dir/a/ok`. A write of `dir / "a" / ".." / "f"`
  makes `dir/a` and lands at `dir/f`.
- `ownerOnly` follows the XDG base directory spec for a missing base folder.
- A write, `copyTo` or `openAppend` with `ownerOnly = true` makes its missing folders the same way. Windows ignores
  it.

**Building a folder whole:**

- The stage is `.<name>.<id>.dir` beside the folder.
- The answer is `Ok` of what the block returned. When the block answered an `Err`, it is `Ok` of that `Err`, with
  nothing placed.
- Something that appears at the path while the block runs is never replaced and is `AlreadyExists`. The exception is
  the JVM and Android gap [Known limits](../README.md#known-limits) describes.
- While the block runs, the build holds a claim `.<name>.<id>.lock` beside the stage, so the stage cannot be
  discarded as a leftover. The claim file goes with the stage.
- The claim's name is one byte longer than a write's temp. A name with no room for it is `NameTooLong` naming
  `LongName.Stage`. That is a name over `MAX_STAGED_NAME_BYTES`.
- `name.nameProblem(staged = true)` answers `TooLongToStage` for exactly those names, so you can check a name someone
  typed before the build.
- You can check a path the same way with `relativePathProblem(staged = true)`. It judges the path's last name that
  way.
- A process killed while the block runs leaves the stage and its claim. [Leftovers](leftovers.md) finds them as one
  `Leftover.StagedFolder`.
- A file in the stage may have any name up to 255 bytes. The default mode still writes it by rename.
- Inside a lambda that is not inline, a plain `return` cannot leave. There `return@createDirectoryAtomically Err(e)`
  also gives up, as does a `bind()` in a `result` block around it.

**Moving:**

- A file already at `target` is replaced on the JVM, Android, Linux and Apple. Windows native is unverified.
- A link at `target` is replaced as a link.
- A file or a symlink moved onto a folder is `NotRegularFile` naming the folder, as `copyTo` answers. That holds even
  for a symlink to a folder, or a dangling one.
- A folder replaces only an empty folder, where the OS allows it.
- A folder moved onto anything else is `NotADirectory` naming `target` with what is there: `Regular` for a file,
  `Symlink` for any symlink.
- A failure making `target`'s folders is what `createDirectories()` returns.
- A missing source is `NotFound` before any folder is made.
- A source or `target` ending in `.` or `..` is the folder it names, as for a removal. No folder that trailing name
  passes through is made.

**Removing and listing:**

- `delete()` of a path nothing is at is `NotFound`.
- `deleteRecursively()` counts what is already gone as removed, so a missing path is `Ok`, while an empty path is
  still `NotFound`.
- `deleteRecursively()` stops at the first entry it cannot remove and returns that failure. That can leave part of
  the tree behind.
- A folder with entries that it may not list is `Inaccessible`.
- A path ending in `.` or `..` names a folder by name alone. `dir / "."` and `dir / "sub" / ".."` both name `dir`,
  whatever `sub` is, so either removes `dir` itself.
- `list()` of anything that is not a folder is `NotADirectory`.

**Symlinks:**

- `createSymlink` stores `target` as given, a missing one included.
- Anything at `link` is `AlreadyExists`.
- It creates no folder, so a missing one is `WriteFailed` with the cause `NotFound`.
- `readSymlink()` gives what a link holds, as written. Anything else is `NotASymlink` naming what is there.
- `switchSymlinkTo` works with the new target missing. With nothing at `link`, it makes the link.
- It stages the new link as `.<name>.<id>.link` beside the old one and moves it over.
- A switch killed partway leaves that staged link, found as a `Leftover.StagedLink`. One that fails otherwise removes
  it again.
- A staged link that will not move over is `WriteFailed` naming `link`, with the staged link as its `at`.
- `switchSymlinkTo` refuses before staging, and keeps what is there as it was:
  - A folder at `link` is `NotRegularFile`.
  - A regular file, or anything else that is no link, is `AlreadyExists`.
- A name over `MAX_STAGED_NAME_BYTES` leaves no room for the staged link's name. It is `NameTooLong` naming
  `LongName.StagedLink`.
- A stage of `createDirectoryAtomically` takes the same longest name. `nameProblem(staged = true)` answers
  `TooLongToStage` for each name refused this way.
- On Windows native, unverified there, a junction reads as a link too. Another kind of reparse point is
  `Inaccessible`. Making a link there is unverified as well.

**Writing to a dot name:** a write or copy to a path ending in `.` or `..` is refused before anything is made. Take
`folder / "a" / "ok" / ".."`:

- With `folder/a` a folder, it is `NotRegularFile` naming a `Directory`.
- With `folder/a` not there, it is `NotFound`.
- With `folder/a` a file, it is `NotADirectory` naming `folder/a`.
- `folder/a/ok` is never made.
