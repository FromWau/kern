# Types

Every call in `fs` that can fail hands back a `Result`. When it fails, the error is a `FileError`, and each case says
what went wrong and where. This page lists `FileError` and every other type the calls take or hand back.

Match on a `FileError` case when your code acts on it. Print the rest.

Part of [`fs`](../README.md).

## Rules

- `FileError` is a sealed interface. Every case has `path: Path`, the path it is about, so `failure.path` reads
  without a `when`.
- Every case takes `path` as its first parameter. The one exception is `InvalidRelativePath`: its `path` is its
  `folder`.
- `cause` is an `IoCause`: `Denied`, `NotFound`, `ReadOnly`, `NoSpace`, `Loop` or `Other`. It is typed only where the
  platform says so without its message being read. Otherwise it is `Other`, and the platform's exception is in
  `exception`.
- `at`, on `WriteFailed` and `RestoreFailed`, is a `FailedAt`. It names the spot the system refused when that spot is
  not `path`, and is null otherwise. Its kind says what the spot is: `Temp`, `Backup`, `LinkTarget`, `Folder` or
  `Claim`.
- **To show a write failure to a user, show `path`**, the file they asked for. Word `at` by its kind, as
  [Tell the user where a write failed](#tell-the-user-where-a-write-failed) does.
- Never show `at.path` on its own. A temp or a claim is a name the user never chose.
- `NameRefused` is the sealed group of `InvalidRelativePath`, `Escapes`, `InvalidName` and `NameTooLong`. Each means
  the name or link target a call was given is not one it makes, and nothing was changed. Check
  `is FileError.NameRefused` to skip such a name and fail on the rest.
- A `FileType` of `Symlink` comes only from a call that looks at a link itself: `fileType(followLinks = false)`,
  `AlreadyExists` and `moveTo`'s `NotADirectory`.

### Details

- Two errors are equal when every field but `exception` is. `toString()` still shows the exception.
- Printed, `Refused` shows its path, `asked` when it differs, `why` and the permissions it saw:
  `Refused(/srv/app: OthersMayWrite, rwxrwxrwx)`.
- When a folder on the way, or the file a link leads to, was refused, it prints as
  `Refused(/srv/app for /srv/app/config/app.toml: OtherOwner)`.
- Printed, `BadName`, `InvalidRelativePath` and `NameTooLong` cut a name over 64 characters, followed by its length in
  bytes. The fields keep the name whole.

## Tasks

### Tell a write failure apart

```kotlin
when (val e = path.writeText(text).errorOrNull()) {
    null -> Unit
    is FileError.WriteFailed -> println("could not write $path: ${e.cause}")
    else -> println("could not write $path: $e")
}
```

To show the folder that refused a write, take `e.at?.path?.parent ?: e.path.parent`.

### Tell the user where a write failed

This wording names the file the user asked for, then says which spot refused.

```kotlin
fun whereRefused(failure: FileError.WriteFailed): String = when (val at = failure.at) {
    null -> "could not write ${failure.path}"
    is FailedAt.Temp -> "could not write ${failure.path}: its temp ${at.path} could not be made or moved"
    is FailedAt.Backup -> "could not write ${failure.path}: its backup ${at.path} could not be made"
    is FailedAt.LinkTarget -> "could not write ${failure.path}: the file it links to, ${at.path}, refused"
    is FailedAt.Folder -> "could not write ${failure.path}: the folder ${at.path} could not be made"
    is FailedAt.Claim -> "could not write ${failure.path}: its claim ${at.path} could not be made"
}
```

### Explain a cause

```kotlin
fun advice(cause: IoCause): String = when (cause) {
    IoCause.Denied -> "you may not do that here"
    IoCause.NotFound -> "a file or folder on the way is missing"
    IoCause.ReadOnly -> "the disk is mounted read-only"
    IoCause.NoSpace -> "the disk, or your quota on it, is full"
    IoCause.Loop -> "a symlink on the way loops, or too many follow in a row"
    IoCause.Other -> "see the exception for why"
}
```

### Describe a file type

```kotlin
fun describe(type: FileType): String = when (type) {
    FileType.Regular -> "a file"
    FileType.Directory -> "a folder"
    FileType.Fifo -> "a named pipe"
    FileType.CharacterDevice -> "a device"
    FileType.BlockDevice -> "a disk or partition"
    FileType.Socket -> "a socket"
    FileType.Symlink -> "a symlink"
    FileType.Other -> "something this platform cannot name"
}
```

## Reference

### FileError

The table gives the short meaning of each case. Details, below the table, has the full rules.

| `FileError` | meaning |
|---|---|
| `NotFound(path)` | nothing at the path |
| `AlreadyExists(path, type: FileType)` | something is in the way of a new link or folder |
| `Inaccessible(path, cause: IoCause, exception: Throwable?)` | the path could not be read or looked up |
| `NotRegularFile(path, type: FileType)` | something other than a file where a file was expected |
| `NotADirectory(path, type: FileType)` | something other than a folder where a folder was expected |
| `NotASymlink(path, type: FileType)` | `readSymlink()` found no symlink at the path but a `type` |
| `AtomicRefused(path, why: AtomicRefusal)` | a `WriteMode.Atomic` write would have renamed the file, and wrote nothing |
| `Refused(path, info: FileInfo, why: RefusedBecause, asked)` | a check refused what is at `path`, and nothing was done |
| `TooLarge(path, limitBytes: Long, atLeastBytes: Long)` | at least `atLeastBytes` bytes, over the limit |
| `WriteFailed(path, cause: IoCause, exception: Throwable?, at: FailedAt?)` | writing, deleting or moving the file, or creating a folder, failed |
| `Escapes(path, boundary: Path, resolved: Path)` | a [Writer](writer.md) call may not reach the path |
| `NameTooLong(path, name: LongName, bytes: Int)` | a name of `bytes` in UTF-8, longer than `LONGEST_NAME_BYTES`, the 255 a name may take |
| `InvalidName(path, reason: NameProblem)` | the path holds what no name may |
| `LockBusy(path, holderPid: Long?, waited: Duration)` | another holder kept the lock past the wait |
| `RunTempClaimed(path, holderPid: Long?)` | `withRunTemp` found the run temp folder at the path already claimed |
| `LockFailed(path, cause: IoCause, exception: Throwable?)` | the lock could not be attempted |
| `LockLost(path)` | `check()` of a lock still held found it no longer on the file at the path |
| `Closed(path)` | an `AppendFile` was used after `close()`: `append` or `lastByte` |
| `FolderReplaced(path)` | the root of a [`Writer`](writer.md) is gone or replaced, so the writer did nothing |
| `InvalidRelativePath(folder: Path, entry: String, problem: RelativePathProblem)` | the name a `Writer` was given is no relative path below its root |
| `PendingBackup(path, backup: Leftover.Backup)` | a write found the backup of a killed in-place write, and wrote nothing |
| `OutputClash(path, firstRelative: Path)` | two entries of a `mirrorTo` source go to one output |
| `MirrorOverlap(path, dst: Path)` | `mirrorTo` was asked to mirror a folder into itself or into a folder above it |
| `RestoreFailed(path, cause: IoCause, exception: Throwable?, backup: Path?, at: FailedAt?)` | a write failed and undoing it failed too, so the file is damaged |

#### Details

- `NotFound`: the path is missing, or is a symlink whose target is missing. It is also a path that runs through a
  file and so cannot resolve.
- `AlreadyExists` comes from `createSymlink` or `createDirectoryAtomically` when something is at the path where they
  would make something new, a dangling symlink included.
- `AlreadyExists` also comes from `switchSymlinkTo` when something that is no link or folder is where it would replace
  a link.
- The `type` of `AlreadyExists` is what is there, read without following a link, so any link is `Symlink`. It is
  `Other` when the path could no longer be looked up.
- `NotRegularFile` is a folder, FIFO, device or socket where a file was expected. It is also:
  - a symlink where a `Writer` write would put a file without `replaceLink`
  - any of them at the output of a `mirrorTo` file when that removal stayed, as `removalsDeclined` lists it
- `NotADirectory` is a file, FIFO, device or socket where a folder was expected. It is also a symlink a folder was
  moved onto, as `Symlink`, such as at the output of a `mirrorTo` folder when that removal stayed, as
  `removalsDeclined` lists it.
- The `why` of `AtomicRefused` is `OtherHardLinks`, `OtherOwner`, `GroupNotKept`, or on Windows native `Symlink`, a
  link it cannot follow.
- `Refused`:
  - `info` is what is there.
  - `asked` is the path the call was given. It differs from `path` when the refused place is a folder on the way, or
    the file by its real path, as through a link.
  - `why` is `Accept` when the `accept` check of `readText` or `readBytes` answered false for the file opened. Nothing
    was read.
  - `why` is `OtherOwner` or `OthersMayWrite` when someone else owns it, or its group or others may write it.
  - `why` is `OthersMayRead` when its group or others may read it. A private `Writer` checks that on its root and on
    each file it reads.
- These calls check for `OtherOwner` and `OthersMayWrite`:
  - a [trusted read](reading.md#read-a-config-only-you-may-change): the file and its folder
  - `singleInstance`: its `state` folder
  - `discard` and `restore` with `throughLinks`: the folder a leftover sits in
  - a [`Writer`](writer.md): its root and what it reads.
- `TooLarge`: a read that crosses the limit stops there instead of measuring the rest.
- `Escapes` means one of three things. In each case nothing was read, written, created, moved, removed or locked.
  - A symlink on the way leads out of `boundary`, the folder the writer keeps to, or steps out of it on its way back
    in.
  - The path lies outside the writer's root, as a leftover handed to `discard` or `restore` without `throughLinks`
    can.
  - The target `switchSymlink` was given for the link at the path leads out by its names.
- The `boundary` of `Escapes` is the writer's root. For a child writer it is the boundary of the writer it was made
  from. For a stage it is the folder the stage builds, named as it will be placed.
- The `resolved` of `Escapes` is where it leads:
  - For a link on the way: the real path of the folder it names. When that has no real path, it is the link's target
    joined to the link's folder and read by name.
  - For a link at the path itself, or the last link of a chain there, or a switched link's target: that target
    joined to the link's folder and read by name. So `current -> /etc` gives `/etc`, and `/srv/app/lib/up -> ../..`
    gives `/srv`.
  - For a path outside the root: its own folder.
- The `name` of `NameTooLong` says which name is too long:
  - `LongName.Path`: a name in the path itself.
  - `PartialBackup`: the partial backup an in-place write would make beside the file.
  - `Stage`: the stage of `createDirectoryAtomically`. Its claim `.<name>.<id>.lock` is the name counted.
  - `StagedLink`: the `.<name>.<id>.link` of `switchSymlinkTo`.
- Printed, each name in the `path` of `NameTooLong` over 64 characters is cut and followed by its own length, your
  name's, so for a `StagedLink` that length differs from `bytes`, the staged name's length.
- A `Writer` answers a name of yours over 255 bytes as `InvalidRelativePath` with `BadName(TooLong)` instead of
  `NameTooLong`. In either case nothing was created.
- The `reason` of `InvalidName` is one of these. Nothing was looked up or created.
  - `NameProblem.Nul`: a NUL character.
  - For `singleInstance`: the name is not one name, as `nameProblem()` says, or is `TooLong` for its lock file
    `.<name>.lock`.
  - `LinksToItself`: the target `Writer.switchSymlink` was given names the link at the path itself.
- The `holderPid` of `LockBusy` is this process's own pid for a holder in this process. For another process:
  - Native Linux and Apple name it.
  - The JVM and Android answer null, since their `FileChannel` cannot say who holds a lock.
  - Windows native answers null, except from `singleInstance`. That names the pid in its pid file while a process
    with it runs, and null otherwise.
- `RunTempClaimed`: the claim belongs to a `withRunTemp` on the same `AppDirs` still running, or to another process
  holding the same fixed run id. `holderPid` names the holder as `LockBusy` does. Nothing was created or cleared.
- `LockFailed`: the lock file would not open, or the OS refused the call.
- `LockLost`: the file at the path was deleted, or replaced by another, so a new holder can lock a file there.
- `FolderReplaced`: the root of the `Writer`, at its real path, was moved or deleted, or another folder or a link
  stands there now. What a build whose folder is replaced leaves is in
  [Build a folder whole](writer.md#build-a-folder-whole).
- `InvalidRelativePath`: the name is no relative path below `folder`, as `relativePathProblem()` says. It is also
  what a call that makes an entry answers for a name shaped like a leftover, `LeftoverName`.
- The `path` of `InvalidRelativePath` is `folder`. Nothing was looked up or done.
- `PendingBackup`: a write of the file at the path found `backup` beside it, the old content a killed in-place write
  copied. That goes for a write through a `Writer` and for a `Path` write or copy.
- For a write through a link, that backup sits beside the link's target. When there are several, `backup` is the
  oldest.
- Nothing was written. Restore or discard the backup, and the write goes through. For a write through a link, restore
  it through a `Writer` with `restore(backup, throughLinks = true)`. An append is never stopped.
- `OutputClash`: the path is the output in the destination. The entry at `firstRelative`, relative to the source, took
  it first in walk order. This entry was neither copied nor made, nor anything below it, on every run.
- `MirrorOverlap`: the path is the source folder, and `dst` is the root of the `Writer` it was to be mirrored into.
  The path is `dst` or lies below it by its `realPath()`, so the removals would delete the source. Nothing was copied
  or removed.
- `RestoreFailed` also comes when `Writer.restore(backup)` failed after emptying the file.
- The `backup` of `RestoreFailed` names the copy to put back. It is null only when there is none: the file was new, or
  the copy is gone.

### IoCause

An `IoCause` turns each platform's failure into one of six cases. This is how each platform maps to them.

- On the JVM and Android, the exception's type decides: `AccessDeniedException` is `Denied`, `NoSuchFileException`
  `NotFound`, `FileSystemLoopException` `Loop` and `ReadOnlyFileSystemException` `ReadOnly`.
- There, a bare `FileSystemException` and java.io's `FileNotFoundException` are `Other`.
- On Linux and Apple, the `errno` decides, for a call this library makes itself. Those calls are a lookup, making a
  folder, listing, opening a file to read, an append, a write's temp file or backup, opening the file a write
  replaces, an in-place write, opening a lock file, or a delete that failed.
- There the `exception` carries the system's message led by the path, as the JVM's do:
  `/home/me/.config/app/.app.toml.<id>.tmp: Permission denied`.
- Windows native types the error code of its own Win32 calls the same way, unverified there. Those calls are making a
  folder, writing a file, an append or a lock, so a file it may not write is `Denied`, and a full disk `NoSpace`.
- Windows native reads files through kotlinx-io, so a file it may not read is `Other`.
- A failure the platform does not name is `Loop` when the symlinks this library follows on the way run past 40, as
  Linux counts. It counts the links at the last name and in every folder above.
- So a link to itself, or a path below one, is `Loop` on the JVM too. The JVM reports both as a bare
  `FileSystemException`.
- Whatever kotlinx-io does itself is `Other`, since its exceptions carry only a message. That is moving on native, and
  deleting and listing on Windows native.
- Removing a symlink on native is this library's own call, typed as above.
- On the JVM and Linux, a write into a folder this process may not write, or onto a file it may not read or write, is
  `WriteFailed(cause = Denied)`.
- There, a file it may not read is `Inaccessible(cause = Denied)`, by `readText`, `readBytes` or as the source of
  `copyTo`. So is a path it may not look up, or a folder it may not list.
- Android and Apple run the same code, untested there.

#### Details

- On `RestoreFailed`, `cause` and `exception` are the undo's rather than the write's, since the undo left the file
  damaged.
- `exception` is null only for a failure this library finds itself with no exception behind it, such as a file that
  shrank while `lastByte()` read it.

### Which path failed

When the system refused a spot other than the file you named, `at` says which. It is one of five kinds of `FailedAt`,
each with the `path` the system refused:

| `FailedAt` | the spot |
|---|---|
| `Temp(path)` | the temp `.<name>.<id>.tmp` a write renames into place, or the stage `.<name>.<id>.dir` or `.<name>.<id>.link` a folder or link is built as |
| `Backup(path)` | the backup `.<name>.<id>.bak` an in-place write copies the old content to, or the `.bak.part` it copies into first |
| `LinkTarget(path)` | the file a symlink at `path` leads to, which the write goes through to |
| `Folder(path)` | a folder on the way to the file that could not be made |
| `Claim(path)` | the lock file that claims a stage, `.<name>.<id>.lock` beside it, or a run's temp folder, `.<run id>.lock` beside it |

- A write through a link into a folder you may not write is `WriteFailed` with the link as `path`. Its `at` is the
  temp beside the link's target, a `Temp`.
- A plain write into such a folder names its `Temp` too.
- `WriteFailed(path, Denied, e1)` equals `WriteFailed(path, Denied, e2)`.

### FileType

- `Regular`, `Directory`, `Fifo`, `CharacterDevice`, `BlockDevice`, `Socket`, `Symlink`, or `Other`.
- A `CharacterDevice` is a stream of bytes, such as `/dev/null`, `/dev/zero` or `/dev/tty`. A `BlockDevice` is a disk
  or partition, such as `/dev/sda`.
- Linux, Apple, and the JVM on a file system with unix attributes tell a FIFO, each kind of device and a socket apart.
  Android reads the same attribute, untested on a device.
- Windows native calls a device such as `NUL` or `COM1` a `CharacterDevice` and a named pipe a `Fifo`, unverified on
  Windows.
  - The C runtime's `stat` is asked first, so a device or pipe may be `NotFound` before kern types it.
  - Looking one up opens it, so looking up a named pipe connects to its server, and reading one likely fails.
  - A device or pipe whose details Windows does not give has `size` 0, `modifiedAt` 1601-01-01, Windows' zero time,
    and `links` null.
  - `fileType(followLinks = false)` may call a device `Regular`, since that call reads attributes alone.
- The JVM on Windows calls each of them `Other`, as it does anything it cannot name.
- `info()` and `fileType()` follow links by default, so they never answer `Symlink`.

### Other types

The other types the calls take or hand back:

- `WriteMode`, a sealed interface of four objects: `Auto`, the default, `Atomic`, `Replace` and `InPlace`, as
  [Writing](writing.md#write-modes) describes.
- `Written(way, info)`, what a write did. `way` is a `WriteWay` enum of how it replaced the content: `Renamed` or
  `InPlace`. `info` is the `FileInfo` of the file it left.
- That `info` is read from the file's own descriptor or handle on Linux, Apple and Windows. On the JVM and Android it
  is looked up by name right after the write.
- Its `info.changedAt` can be later than its `modifiedAt` with nothing changed since. Setting `modifiedAt` moves
  `changedAt`, and so can the rename. An `info()` read next matches it.
- `Copied`, what `copyTo` did: a `Written`, or `Copied.Unchanged(info)` for a copy onto the file itself. Either has
  `info`.
- `Symlinks`, an enum for `walkTopDown` and `mirrorTo`, as [Symlinks](walking.md#symlinks) describes. A walk's default
  is `Inside`, and a mirror's is `AsLinks`.
  - `Inside` follows only a link that ends inside the root. `Anywhere` follows every link. Both walk each real folder
    once, one inside the root under its own path.
  - `NoLinks` follows none, and passes each over as `Skipped(Link)`.
  - `AsLinks` follows none, and yields each as a `WalkEntry.Link`.
- `WalkEntry`, from `walkTopDown`, as [Walking](walking.md) describes. It is one of:
  - `File(path, relative, size: Long, modifiedAt: Instant, viaLink: Boolean)`
  - `Folder(path, relative)`, also what `enter` is asked about
  - `Link(path, relative, target: Path)`, under `AsLinks` only
  - `Skipped(path, relative, why: WalkSkip)`.
- `WalkFailure(path, relative, error: FileError)`, for what a walk could not read.
- `MirrorReport(copied: List<Mirrored>, unchanged: List<Mirrored>, removed: List<Path>,
  held: List<WalkEntry.Skipped>, passedOver: List<WalkEntry.Skipped>, failures: List<WalkFailure>,
  notRemoved: List<FileError>, removalsDeclined: List<Removal>, sourceEmpty: Boolean,
  unclean: List<MirrorReport.Unclean>)`, from `mirrorTo()`, as [Mirror report](walking.md#mirror-report) describes.
- In a `MirrorReport`, `removed` and each declined `Removal` are relative to the destination. `isClean` is
  `unclean.isEmpty()`.
- `MirrorReport.Unclean`, one reason a mirror is not clean. Each names the entry of the report it is:
  - `Failed(failure: WalkFailure)`
  - `NotRemoved(error: FileError)`
  - `Kept(removal: Removal)` for a declined removal
  - `Held(entry: WalkEntry.Skipped)` for a held entry `exclude` or `enter` did not ask for that is no link to the
    destination
  - `LinkNotMade(entry: WalkEntry.Skipped)` for a link `AsLinks` yielded that leads out.
- `Mirrored(relative: Path, output: Path)`, a file or link in `copied` or `unchanged`. `relative` is its path relative
  to the source. `output` is its output relative to the destination. The two differ where `outputOf` renamed it.
- `RemovalPlan(removals: List<Removal>, files: Int, folders: Int, dstEntries: Int, sourceEmpty: Boolean)`, what
  `mirrorTo`'s `approveRemovals` is asked about. `approveRemovals` answers the removals to carry out.
- A `RemovalPlan` holds the paths it would remove and why, and how many are files and folders. It holds how many
  entries the destination holds, and whether the source's walk yielded no file or folder at all.
- Its `defaultApproved()` answers what the default `approveRemovals` does.
- `Removal(path: Path, why: RemovalReason)`, one path a mirror would remove, relative to the destination. `why` is
  `Stale`, `InTheWay`, or `AtPassedOver(skip: WalkSkip, place: Path)`, as [Mirror rules](walking.md#mirror-rules)
  describes.
- `AtPassedOver` is for what lies at the place of an entry the walk passed over, also for a removal at the output
  `outputOf` gave it. `place` is that entry's path relative to the source.
- `LeftoverScan(found: List<Leftover>, throughLinks: List<Leftover>, failed: List<ScanFailure>,
  passedOver: List<Path>)`, from `leftoversIn()` and `AppDirs.leftovers()`, as [Leftovers](leftovers.md#reference)
  describes.
- In a `LeftoverScan`, `passedOver` holds each link to a folder the scan would have entered. `LeftoverScan.EMPTY` is a
  scan that found nothing.
- `ScanFailure(path, error: FileError)`, each entry or folder a scan could not check.
- `byFile(throughLinks)`, the leftovers of each file, and `oldestBackups(throughLinks)`, the backup to restore first
  for each file. Both are on a `LeftoverScan`.
- `LongName`, the name `NameTooLong` found too long: `Path`, `PartialBackup`, `Stage` or `StagedLink`.
- `NameProblem`, what `InvalidName` found, or what `nameProblem()` found in a name:
  - `Nul`, `Blank`, `Dot`, `DotDot`, `Separator`
  - `TooLong`, over 255 bytes in UTF-8
  - `LeftoverName`, shaped like a copy a write leaves, such as `.notes.md.<id>.tmp`
  - `LinksToItself`, which only `InvalidName` from `Writer.switchSymlink` carries.
- `NameProblem.TooLongToStage`, a name past `MAX_STAGED_NAME_BYTES`, 212 bytes, and up to 255. Only
  `nameProblem(staged = true)` and `relativePathProblem(staged = true)` answer it. A stage or a link switch answers
  such a name as `NameTooLong`.
- `RelativePathProblem`, what `relativePathProblem()` found in a relative path: `Absolute`, or `BadName(index: Int,
  name: String, problem: NameProblem)` for the first name it refuses, as [Path names](../README.md#path-names)
  describes.
- `FileInfo(type: FileType, size: Long, modifiedAt: Instant, changedAt: Instant?, permissions: Permissions?,
  ownedByMe: Boolean?, links: Int?)`, from `info()`, as [Reading](reading.md#look-at-what-is-at-a-path) describes.
- `Leftover`, from `leftovers()` and `leftoversIn()`, as [Leftovers](leftovers.md) describes. It is `Temp`,
  `UnnamedTemp`, `Backup`, `PartialBackup`, `StagedFolder` or `StagedLink`.
- Each `Leftover` has `copy`, `file`, `startedAt: Instant?`, `via: Path?` and `fileMayBeDamaged`, which is true for a
  `Backup` only.
- The five kinds that name a file declare `file: Path`, and `UnnamedTemp` has null, so code holding a plain
  `Leftover` sees `Path?`.
- `Writer.discard(leftover, throughLinks = false)` and `Writer.restore(backup, throughLinks = false)` deal with one
  `Leftover`.
- `Permissions`, from `permissions()`: a value class over the mode's `bits`, whose `toString()` reads `rw-r--r--`.
  It has `ownerRead`, `ownerWrite`, `ownerExecute`, `anyExecute`, `othersMayRead` and `othersMayWrite`.
- `AppendFile`, from `openAppend()`: `path`, `append(bytes): EmptyResult<FileError>`,
  `state(): Result<AppendState, FileError>`, `lastByte(): Result<Byte?, FileError>` and `close()`. The extension
  `appendLine(text): EmptyResult<FileError>` goes with it.
- `AppendState`, an enum: `Same`, `Moved`, `Deleted`, `Unknown` or `Closed`, as
  [Appending](appending.md#reference) describes.
- `Reopen`, for `openAppend`: `Never`, the default, or `WhenMoved(checkEvery: Duration = Duration.ZERO,
  onReopen: (opened: AppendFile, replaced: AppendState) -> Unit = { _, _ -> })`, as
  [Appending](appending.md#append-to-a-log-another-tool-rotates) describes.
- `Reader`, from `reader()`, `privateReader()` and `Writer.reader`. Every `Writer` is one too. It has `root`,
  `readText`, `readBytes`, `readPrefix`, `readSymlink`, `realName`, `info` and `list`, as
  [Read without making anything](writer.md#read-without-making-anything) describes.
- `FailedAt`, the `at` of `WriteFailed` and `RestoreFailed`, as [Which path failed](#which-path-failed) lists it.
- `FileLock`, from `tryLock()`. It has its `path`, `close()` to release it, and `check()`, whether it still holds the
  file at its path, as [Locking](locking.md#hold-a-lock-for-a-whole-run-and-notice-losing-it) describes.
- `InstanceLock`, from `singleInstance()`. It has its `lockFile` and `pidFile`, `check()` as a `FileLock` has it, and
  `close()` to release the lock. It is `AutoCloseable`, so `use { }` releases it.
- `StaleRuns`, from `staleTempRuns()`: the `stale` folders, and the `failed` ones as `StaleFailure`s.
- `StaleCleanup(cleared: List<Path>, failed: List<StaleFailure>)`, from `clearStaleTempRuns()`: the folders it
  cleared, and those it could not check or clear.
- `StaleFailure(folder: Path, error: FileError)`: the run folder that could not be checked or cleared, and why.
