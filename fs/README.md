# fs

Files fail in ordinary ways. A file is missing, a name is too long, a write is cut short when the app is killed.
`fs` gives you `Path` helpers for Kotlin Multiplatform that hand those failures back as typed errors. Nothing throws,
so you deal with a failure where it happens.

Three habits cover most code:

- Write your app's own folders through a [Writer](docs/writer.md).
- Use the plain `Path` calls for a path the user picked.
- Read with `trusted = true` when a file must be yours alone.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:fs:$kernVersion")
}
```

- KMP consumers put it in `commonMain`.
- [`dirs`](../dirs/README.md), kern's `result` and kotlinx-io come along as `api`.
- `Path` is `kotlinx.io.files.Path`. A file that names it imports it from there.
- Needs what [every kern module needs](../README.md#requirements).
- For tests, [`fs-test`](../fs-test/README.md) makes a scratch folder and the links, modes and FIFOs a test sets up.

## Which do I use?

Start from what you want to do. Each row names the calls to reach for and the page that explains them.

| you want to | use | page |
|---|---|---|
| build a whole app: a command-line tool, a daemon, a site builder | `BaseDirs`, `singleInstance`, `withRunTemp`, `mirrorTo` | [Whole apps](docs/apps.md) |
| write, read, move or lock files in your app's own folders | `dirs.data.writer()`, `privateWriter()` for secrets | [Writer](docs/writer.md) |
| write a file at a path the user picked | `path.writeText(text)`, `copyTo` | [Writing](docs/writing.md) |
| read a file | `path.readText(maxBytes)`, `trusted = true` | [Reading](docs/reading.md) |
| visit or copy a tree | `walkTopDown`, `mirrorTo` | [Walking](docs/walking.md) |
| clean up after a crash | `leftovers`, `restore`, `discard` | [Leftovers](docs/leftovers.md) |
| keep two runs apart | `withLock`, `singleInstance` | [Locking](docs/locking.md) |

- The second and third rows split on who owns the folder. Your app's own folders go through a `Writer`, so no name
  and no symlink reaches outside. A path the user picked goes through the plain `Path` calls.
- Add `trusted = true` to a read for a config only you may change.

## Getting started

This program reads its settings, falls back to defaults when the file is missing, and writes the result back.

```kotlin
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.current
import com.fromwau.kern.fs.*
import com.fromwau.kern.result.*

sealed interface AppError : ResultError {
    data class Dirs(val error: DirsError) : AppError
    data class File(val error: FileError) : AppError
}

fun saveSettings(): EmptyResult<AppError> = result {
    val dirs = BaseDirs.current().flatMap { it.forApp("myapp") }.bind(AppError::Dirs)
    val settings = dirs.config / "settings.toml"
    // A missing file reads as the defaults. Checking exists() first would race a file that goes away in between.
    val text = settings.readText(maxBytes = 1_048_576).orIfNotFound { "theme = \"light\"" }.bind(AppError::File)
    settings.writeText(text).bind(AppError::File)
}

fun main() {
    saveSettings().onError { println("settings not saved: $it") }
}
```

- `BaseDirs.current()` works in the common code of an app with no Android target. An app with one keeps its own
  factory, as the [dirs README](../dirs/README.md#make-a-factory-per-platform) shows.
- `current` is an extension. It takes an import of its own unless you import `com.fromwau.kern.dirs.*`.
- Every write creates the folders it needs.

## Rules

These hold for every call in the module.

- Nothing throws. A failure is a `FileError` in the `Result`. Every case names the path it is about.
- Every path a call takes is checked before the system sees it, a `target` included:
  - An empty path names nothing. Every call answers `NotFound`, and `exists()` answers `false`.
  - A NUL character is `InvalidName(path, NameProblem.Nul)`, since the system would read only up to it.
  - A name over `LONGEST_NAME_BYTES`, 255 bytes in UTF-8, is `NameTooLong(path, LongName.Path, bytes)`. That goes
    for the last name and for a folder's. `bytes` is the longest name's length.
  - `exists()` answers `false` for a NUL or a name that is too long.
- The target `createSymlink` writes into a link gets only the NUL and length checks, since a link may hold an empty
  target.
- Path names are computed from the string alone and never touch the filesystem. The two exceptions are `realPath()`
  and `canonicalPath()`.

## Tasks

### Build a path

```kotlin
val settings = dirs.config / "settings.toml"
(dirs.config / "drafts" / "../app.toml").canonicalPath()   // the real config folder / app.toml, existing or not
```

`/` appends what you give it, as written. `canonicalPath()` resolves the part that exists and adds the rest by name.
[Path names](#path-names) has the edge cases.

### Check a name someone typed

```kotlin
name.nameProblem()?.let { problem -> return println("$name is not a file name: $problem") }
(dirs.data / name).writeText(text).onError { return report(it) }
```

`nameProblem()` tells you what keeps a string from being one name in a folder, or null when nothing does. It answers
the first problem that applies, in this order:

- `Nul`, `Blank`, `Dot`, `DotDot`.
- `Separator` for a `/` or a `\`, on every platform.
- `TooLong` past 255 bytes.
- With `staged = true`, `TooLongToStage` for a name too long to stage, which comes before `LeftoverName`.
- Last, `LeftoverName` for a name shaped like a copy a write leaves, such as `.notes.md.<id>.tmp`.

### Check a relative path from a manifest

```kotlin
for ((entry, bytes) in manifest) {
    entry.relativePathProblem()?.let { problem ->
        return println("$entry is not a path in the package: $problem")
    }
    stage.writeBytes(entry, bytes).onError { return report(it) }
}
```

`relativePathProblem()` answers one of two problems:

- `Absolute` for a path that starts at a root: `/`, `\` or a drive such as `C:`.
- `BadName(index, name, problem)` for the first name `nameProblem()` refuses. `index` counts from zero.

It reads only the string, so write through a [Writer](docs/writer.md), which also refuses a link on the way that leads
out.

## Pages

Each page covers one job in depth.

| page | what it covers | main calls |
|---|---|---|
| [Writer](docs/writer.md) | an app folder no name and no symlink reaches outside; private folders | `writer`, `privateWriter`, `reader` |
| [Reading](docs/reading.md) | looking a path up, reading a file, a file only you may change, a config that reloads | `readText`, `info`, `orIfNotFound` |
| [Writing](docs/writing.md) | replacing a file by rename or in place, `WriteMode`, `ownerOnly`, secrets, copying | `writeText`, `copyTo` |
| [Appending](docs/appending.md) | adding to the end of a file, logs another tool rotates | `openAppend`, `Reopen.WhenMoved` |
| [Folders and moves](docs/folders-and-moves.md) | creating folders, building one whole, moving, removing, listing, symlinks | `createDirectories`, `createDirectoryAtomically`, `moveTo`, `switchSymlinkTo` |
| [Walking](docs/walking.md) | every entry under a folder, which links to follow, mirroring a tree | `walkTopDown`, `mirrorTo` |
| [Leftovers](docs/leftovers.md) | what a killed write or folder build left, restoring and discarding it | `leftoversIn`, `AppDirs.leftovers` |
| [Locking](docs/locking.md) | a lock for a block or a run, a lost lock, per-key locks, one instance of an app | `withLock`, `tryLock`, `singleInstance` |
| [A run's temp folder](docs/run-temp.md) | this run's scratch folder, clearing what crashed runs left | `withRunTemp`, `clearStaleTempRuns` |
| [Whole apps](docs/apps.md) | a command-line tool, a daemon and a site builder, each from `main` to its exit code | `forApp`, `singleInstance`, `withRunTemp`, `mirrorTo` |
| [Types](docs/types.md) | `FileError` and its causes, `FileType`, every other type | `FileError`, `IoCause`, `FileType` |

## Reference

You do not need this part on a first read. Come back when a path does something you did not expect, or before you
rely on a write surviving a crash.

### Path names

- `dir / "name"` appends as written. A separator adds more than one segment, and `""` adds none.
- An absolute right side such as `"/x"` goes below `dir` rather than replacing it.
- `".."` stays a segment, so `(dir / "..").parent` is `dir`.
- `dir / path` takes a `Path` by the same rule, so a walk entry's `relative` goes in as `dst / e.relative`.
- `extension` is everything after the last dot of the name: `archive.tar.gz` gives `gz`. It is null with no dot
  (`Makefile`), a trailing dot (`a.`) or only a leading one (`.bashrc`). `nameWithoutExtension` is the rest.
- `expandTilde(home)` expands `~` and `~/...`. It leaves `~bob/...` alone, so the result can still be relative.
- `path.relativeTo(base)` is the rest of `path` below `base`. `/a/b/c` relative to `/a` is `b/c`, and `base` itself
  gives `Path("")`. It compares whole names as written and resolves no link and no `..`.
- `relativeTo` is null when `path` is not under `base`, or when only one of the two is absolute. It is also null when
  the rest holds `..` (`/a/../b` relative to `/a`). `a.relativeTo(Path("."))` is null, while `./a` gives `a`.
- On Windows, spell both paths of `relativeTo` the same way, for example both from `realPath()`.
- `realPath()` looks the path up, so a missing one is `NotFound`.
- `canonicalPath()` resolves the longest part that exists, as `realPath()` does. It adds the missing rest by name,
  drops `.`, and lets each `..` take back the name before it.
- `canonicalPath()` takes a relative path from the working directory. It creates nothing. A missing rest below a
  regular file is `NotFound`.
- In `relativePathProblem()`, `a//b` and `bin/` hold an empty name (`Blank`). A `\` inside a name is `Separator`. The
  empty string is `BadName(0, "", Blank)`.

### Every call

All of these are extensions on kotlinx-io's `Path` unless they say otherwise.

```kotlin
operator fun Path.div(child: String): Path
operator fun Path.div(child: Path): Path
val Path.extension: String?
val Path.nameWithoutExtension: String
fun Path.expandTilde(home: Path): Path
fun Path.relativeTo(base: Path): Path?
fun String.nameProblem(staged: Boolean = false): NameProblem?
fun String.relativePathProblem(staged: Boolean = false): RelativePathProblem?

fun Path.exists(): Boolean
fun Path.readText(
    maxBytes: Long? = null,
    trusted: Boolean = false,
    streams: Boolean = false,
    accept: ((FileInfo) -> Boolean)? = null,
): Result<String, FileError>
fun Path.readBytes(
    maxBytes: Long? = null,
    trusted: Boolean = false,
    streams: Boolean = false,
    accept: ((FileInfo) -> Boolean)? = null,
): Result<ByteArray, FileError>
fun Path.readPrefix(maxBytes: Long): Result<ByteArray, FileError>
fun Path.fileType(followLinks: Boolean = true): Result<FileType, FileError>
fun Path.fileSize(): Result<Long, FileError>
fun Path.modifiedAt(): Result<Instant, FileError>
fun Path.permissions(): Result<Permissions?, FileError>
fun Path.info(): Result<FileInfo, FileError>
fun Path.isSymlink(): Result<Boolean, FileError>
fun Path.realPath(): Result<Path, FileError>
fun Path.canonicalPath(): Result<Path, FileError>
fun Path.readSymlink(): Result<Path, FileError>
fun Path.list(): Result<List<Path>, FileError>
fun Path.walkTopDown(
    symlinks: Symlinks = Symlinks.Inside,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
): Result<Sequence<Result<WalkEntry, WalkFailure>>, FileError>
fun Path.mirrorTo(
    dst: Writer,
    symlinks: Symlinks = Symlinks.AsLinks,
    exclude: Set<Path> = emptySet(),
    enter: (WalkEntry.Folder) -> Boolean = { true },
    keep: (relative: Path) -> Boolean = { false },
    approveRemovals: (plan: RemovalPlan) -> List<Removal> = { it.defaultApproved() },
    outputOf: (relative: Path) -> String = { /* its names joined by `/` */ },
    isCurrent: (source: WalkEntry.File, existing: FileInfo) -> Boolean = { source, existing ->
        existing.size == source.size && existing.modifiedAt == source.modifiedAt
    },
    copy: (source: WalkEntry.File, out: Writer, name: String) -> EmptyResult<FileError> = { source, out, name ->
        out.copyFrom(source.path, name, keepAttributes = true).map { }
    },
): Result<MirrorReport, FileError>
inline fun <S> Result<S, FileError>.orIfNotFound(value: () -> S): Result<S, FileError>
fun <S> Result<S, FileError>.orNullIfNotFound(): Result<S?, FileError>

fun Path.writeText(
    text: String,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Written, FileError>
fun Path.writeBytes(
    bytes: ByteArray,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Written, FileError>
fun Path.copyTo(
    target: Path,
    mode: WriteMode = WriteMode.Auto,
    ownerOnly: Boolean = false,
    executable: Boolean = false,
    keepAttributes: Boolean = false,
    modifiedAt: Instant? = null,
): Result<Copied, FileError>
fun Path.openAppend(
    reopen: Reopen = Reopen.Never,
    ownerOnly: Boolean = false,
): Result<AppendFile, FileError>
fun Path.leftovers(): Result<List<Leftover>, FileError>
fun Path.leftoversIn(depth: Int = 0): Result<LeftoverScan, FileError>
fun AppDirs.leftovers(): Result<LeftoverScan, FileError>

fun Path.createDirectories(ownerOnly: Boolean = false): EmptyResult<FileError>
inline fun <T> Path.createDirectoryAtomically(build: (stage: Path) -> T): Result<T, FileError>
fun Path.delete(): EmptyResult<FileError>
fun Path.deleteRecursively(): EmptyResult<FileError>
fun Path.moveTo(target: Path): EmptyResult<FileError>
fun Path.createSymlink(target: Path): EmptyResult<FileError>
fun Path.switchSymlinkTo(target: Path): EmptyResult<FileError>
const val MAX_STAGED_NAME_BYTES: Int

val DEFAULT_LOCK_WAIT: Duration
inline fun <T> Path.withLock(
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: () -> T,
): Result<T, FileError>
fun Path.tryLock(): Result<FileLock, FileError>
fun AppDirs.singleInstance(name: String): Result<InstanceLock, FileError>
fun AppDirs.singleInstance(key: Path): Result<InstanceLock, FileError>
fun currentProcessId(): Long

inline fun <T> AppDirs.withRunTemp(block: (temp: Writer) -> T): Result<T, FileError>
fun AppDirs.staleTempRuns(): Result<StaleRuns, FileError>
fun AppDirs.clearStaleTempRuns(): Result<StaleCleanup, FileError>

fun Path.writer(create: Boolean = true): Result<Writer, FileError>
fun Path.privateWriter(create: Boolean = true): Result<Writer, FileError>
fun Path.reader(): Result<Reader, FileError>
fun Path.privateReader(): Result<Reader, FileError>
interface Reader {
    val root: Path
    fun readText(
        name: String,
        maxBytes: Long? = null,
        trusted: Boolean = false,
        streams: Boolean = false,
    ): Result<String, FileError>
    fun readBytes(
        name: String,
        maxBytes: Long? = null,
        trusted: Boolean = false,
        streams: Boolean = false,
    ): Result<ByteArray, FileError>
    fun readPrefix(name: String, maxBytes: Long): Result<ByteArray, FileError>
    fun info(name: String, followLinks: Boolean = true): Result<FileInfo, FileError>
    fun readSymlink(name: String): Result<Path, FileError>
    fun realName(name: String): Result<String?, FileError>
    fun list(folder: String? = null): Result<List<String>, FileError>
}
class Writer : Reader {
    val isPrivate: Boolean
    val reader: Reader
    fun writeText(
        name: String,
        text: String,
        inPlace: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError>
    fun writeBytes(
        name: String,
        bytes: ByteArray,
        inPlace: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError>
    fun copyFrom(
        source: Path,
        name: String,
        keepAttributes: Boolean = false,
        executable: Boolean = false,
        replaceLink: Boolean = false,
        modifiedAt: Instant? = null,
    ): Result<Written, FileError>
    fun openAppend(
        name: String,
        reopen: Reopen = Reopen.Never,
        replaceLink: Boolean = false,
    ): Result<AppendFile, FileError>
    fun delete(name: String): EmptyResult<FileError>
    fun deleteRecursively(name: String): EmptyResult<FileError>
    fun move(name: String, toName: String): EmptyResult<FileError>
    fun createDirectories(name: String): EmptyResult<FileError>
    fun switchSymlink(name: String, target: Path): EmptyResult<FileError>
    fun tryLock(name: String): Result<FileLock, FileError>
    inline fun <T> withLock(name: String, wait: Duration = DEFAULT_LOCK_WAIT, block: () -> T): Result<T, FileError>
    fun writer(name: String): Result<Writer, FileError>
    fun privateWriter(name: String): Result<Writer, FileError>
}
inline fun <T> Writer.createDirectoryAtomically(name: String, build: (stage: Writer) -> T): Result<T, FileError>
fun Writer.leftovers(depth: Int = 0): Result<LeftoverScan, FileError>
fun Writer.discard(leftover: Leftover, throughLinks: Boolean = false): EmptyResult<FileError>
fun Writer.restore(backup: Leftover.Backup, throughLinks: Boolean = false): EmptyResult<FileError>
```

The four `Writer.` calls after the class are extensions. Each needs its import, such as
`com.fromwau.kern.fs.restore`, or the star import `com.fromwau.kern.fs.*`.

### Known limits

Some things the library cannot promise. Read these before you count on a write surviving a crash, a power cut or a
second writer.

**A full disk** is `NoSpace` only where the system's error code says so. This library never reads a message to type
a cause.

- Linux and Apple: `NoSpace` for the calls this library makes itself, such as an append, a write's temp file or an
  in-place write.
- Windows native: `NoSpace` for its own Win32 calls, such as writing a file, an append or a lock. This is unverified
  there.
- JVM and Android: `Other`, because Java raises a bare `IOException` for `ENOSPC`.
- Windows native, for what it does through kotlinx-io, such as reading: `Other`.

**Long paths.** A walk or a mirror that meets a path past the system's limit reports that entry as a failure.
[Walk](docs/walking.md#walk) describes it.

**Durability.** kotlinx-io forces nothing to disk on its own, so an in-place write survives a crashed process but not a
power cut. A rename write forces the temp file and then the folder: `fsync`, `FileChannel.force` on the JVM,
`FlushFileBuffers` on Windows. macOS's plain `fsync` does not flush the drive's cache, and `F_FULLFSYNC` is not used,
so a power cut there can still lose a write that returned `Ok`.

**Concurrent writes.** An in-place write is not atomic. That is `WriteMode.InPlace`, or `Auto` when it falls back.

- Readers can see the file half-written while it runs.
- Only one write may run on a file at a time. Two at once can mix, and a failed one can undo another that finished.
- A rename write is atomic for readers. But of two at once, the last rename wins and the other is lost.

**A killed write** leaves something beside the file, depending on when it stopped. `<id>` identifies the write, so a
copy of your own such as `.app.toml.before-my-edit.bak` is never counted.

- During a rename write: the file as it was or fully replaced. At most an orphan `.<name>.<id>.tmp` stays (or
  `.<id>.tmp` for a long name). It is found as `Leftover.Temp` or `Leftover.UnnamedTemp`, and it is safe to delete.
- During an in-place write: a half-written file and the backup `.<name>.<id>.bak`, found as `Leftover.Backup`. The
  file may still parse and say less, so check both copies.
- While the backup was being copied: only `.<name>.<id>.bak.part`, a `Leftover.PartialBackup`. The file is untouched.
- During an in-place write that creates the file: a half-written new file. There is nothing to back up or find.

**Temp and backup files** sit beside the file.

- They need write permission on the folder, not only on the file.
- Their names are longer than the file's, as [Writing](docs/writing.md#reference) counts. The limit is 255 bytes in
  UTF-8 everywhere, Windows' UTF-16 count included.
- A name with no room for `.<name>.<id>.tmp` is still written by rename, through an `UnnamedTemp` named `.<id>.tmp`.
- Only an in-place write of a file already there needs the room, for its backup. One without it is `NameTooLong`.
- Both are created afresh, never over a file at their name. They get the file's permissions, or `600` with
  `ownerOnly`, before any content goes in.
- The JVM copies only the read, write and execute bits, not setuid, setgid or sticky. Windows native copies the
  read-only flag. The JVM on Windows leaves the copy to the folder.

**A `Writer` on the JVM, Android, macOS and Windows** can follow a folder swapped for a link just as a call reaches
it. [Writer](docs/writer.md#reference) says more.

**`createDirectoryAtomically` on the JVM and Android** can replace an empty folder made at the path between the last
check and the rename. java.nio has no rename that refuses one. A folder with entries, a file or a link there is
`AlreadyExists`.

- Linux native (`RENAME_NOREPLACE`), Apple (`RENAME_EXCL`) and Windows native (no `MOVEFILE_REPLACE_EXISTING`,
  unverified) refuse it.
- The exception is Linux before 3.15, or a file system without those flags.

**Names Windows reserves are not refused.** Only a NUL and a name over 255 bytes are refused. Neither `nameProblem()`
nor `relativePathProblem()` checks more, so these go to Windows as written: `CON`, `NUL`, `COM1`, a name ending in a
dot or space, or one holding `<`, `>`, `:`, `"`, `|`, `?` or `*`. Windows then refuses it, trims it or reads a device.

**Windows native:**

- Whether a rename replaces an existing file in one step is unverified, so `Auto` writes an existing file in place,
  and `Atomic` renames regardless.
- Ownership is not checked, so an `Atomic` write can hand another user's file to you.
- kotlinx-io cannot follow a link to an existing file, so `Auto` and `Replace` write through it in place and keep the
  link, and `Atomic` refuses with `AtomicRefused(Symlink)`.
- Undoing a failed first write through a link whose target is missing can remove the link. The half-written file
  then stays at its target.
- `copyTo` tells the source by its real path, and two hard links of one file have different real paths, so copying a
  file onto a hard link of itself can empty it.
- File calls use the ANSI Windows APIs. A path with non-ASCII characters, such as `Jürgen`, likely fails. The JVM on
  Windows is unaffected.
- Looking up or reading a named pipe opens it, which connects to its server. This is unverified.
- A `streams` read of a serial port such as `COM3` opens it several times, for the lookups and then the read. Opening
  one can reset a board such as an Arduino. This is unverified.

**Links on Windows**, unverified there:

- Native counts every reparse point as a symlink, junctions included.
- The JVM asks `Files.isSymbolicLink`, which likely answers false for a junction, so a walk under `NoLinks` or
  `AsLinks` may enter one.
- `realPath()` uses `toRealPath()` on the JVM. On native it uses the final path name, without its `\\?\` prefix.

**Run temps on Windows.** `withRunTemp` deletes its lock file while still holding the lock. Windows allows that only
because the file is opened with share-delete. This is unverified on Windows.

## License

[Apache-2.0](../LICENSE).
