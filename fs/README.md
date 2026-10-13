# fs

`Path` helpers for Kotlin Multiplatform that return typed errors instead of throwing: read, write by rename or in
place, list, walk, move, delete and lock files, and claim a run's temp folder.

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

KMP consumers put it in `commonMain`. [`dirs`](../dirs/README.md), kern's `result` and kotlinx-io come along as
`api`, since `AppDirs`, `Result`, `ResultError` and `Path` appear in this module's signatures. The JVM and Android
artifacts are Java 17 bytecode, so those two need JDK 17 or newer, and Android needs `minSdk` 26 and `compileSdk`
37; the native targets have no such requirement.

## Path names

None of these touch the filesystem.

`dir / "name"` appends what it is given as written: a separator in it adds more than one segment, `""` adds none,
and `".."` stays a segment, so its `parent` is `dir` rather than the folder above. `extension` is everything after
the last dot of the file name, so `archive.tar.gz` gives `gz`; a leading dot marks a hidden file, so `.bashrc` has
none. `nameWithoutExtension` is the rest. `expandTilde(home)` expands `~` and `~/...` and leaves `~bob/...` alone,
so the result can still be relative.

## Path helpers

Nothing here throws. A failure is a `FileError` in the `Result`, and every case names the path it is about. An
empty path names nothing, and every call answers it as `NotFound`.

```kotlin
val file = dirs.config / "app.toml"

file.readText(maxBytes = 1_048_576)   // Result<String, FileError>
file.writeText("theme = \"dark\"")     // EmptyResult<FileError>
dirs.state.createDirectories()        // EmptyResult<FileError>
```

- **Reading.** `exists()` follows symlinks, and answers `false` for a path that cannot be looked up as well as
  for one that is not there. A read that returns a `Result` keeps those apart: `Inaccessible` for a path it may
  not look at, `NotFound` for one that is not there. `fileType()` says what is at a path, and `fileSize()` how
  many bytes a regular file holds; anything else is `NotRegularFile`. `modifiedAt()` is when the entry was last
  written, as a `kotlin.time.Instant`, to the precision the file system keeps.
  `readText(maxBytes)` refuses a directory, FIFO, device or socket before opening it, since opening a FIFO
  blocks until something writes to it. A file longer than `maxBytes` is `TooLarge`, and bytes that are not
  valid UTF-8 read as U+FFFD. `readBytes(maxBytes)` reads the same way and hands back the bytes, for a caller
  that decodes them another way or refuses a file that is not UTF-8.
- **Writing.** `writeText` and `writeBytes` replace a file in one of two ways, chosen by `mode`:
  - **By rename.** The content goes into a temp file `.<name>.<id>.tmp` beside the file, which is forced to disk
    and renamed over it, so readers see the old content or the new and never part of it, and a failure leaves
    the file as it was. The new file takes the old one's permissions and group, but it is a new file: other hard
    links keep the old content, it belongs to you, and timestamps, ACLs and extended attributes start fresh.
  - **In place.** The old content is copied to a backup beside the file, then the file itself is written, so its
    inode, owner and links stay. A failed write moves the backup back; a successful one tries to delete it, and
    one that cannot be deleted stays. If undoing a failed write fails too, the result is `RestoreFailed`.

  `WriteMode.Auto`, the default, renames only a file with no other hard links that belongs to you and whose
  group the new file can be given; otherwise, or when its links, owner or group cannot be read or kept, it writes
  in place. `WriteMode.Atomic` always renames: it refuses with `WriteFailed` a file it knows a rename would change,
  one with other hard links, another owner or a group the new file cannot be given, and renames one whose links or
  owner it cannot read. `WriteMode.InPlace` always writes in place. A new file is written by rename in every mode but
  `InPlace`. The JVM on Windows reads no link count or uid, so there `Auto` writes an existing file in place and
  `Atomic` renames it. A file that cannot be read or written is refused either way, and anything at the path that is not
  a regular file is `NotRegularFile`, the same answer a read gives: opening a FIFO waits on a reader that may never
  come, and a device node would be replaced by a regular file.
- **Leftover backups.** `leftoverBackups()` lists the backups a write left beside a file. A write disposes of
  its own when it can, so one left behind is a reason to check the file rather than proof that it is wrong, and
  neither copy is the one to trust by default. It is the one call here whose error names the folder instead of
  the file, since reading the folder is what can fail.
- **Folders.** Every write creates missing parent folders. `createDirectories()` works like `mkdir -p`, also
  when another process creates the same folders at the same time. A file standing at the path or in place of a
  parent is `NotADirectory` naming that file, and a path it may not look up is `Inaccessible` naming that path,
  rather than a folder it reports as having failed to create.
- **Removing and listing.** `delete()` removes a file, symlink or empty folder, and `deleteRecursively()` clears a
  whole tree. Both take a symlink as a link, so what it points at stays, and a link whose target is missing goes
  too. `deleteRecursively()` stops at the first entry it cannot remove and returns that failure, which can leave
  part of the tree behind. `list()` gives a folder's entries as full paths in name order; anything that is not a
  folder is `NotADirectory`.
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
  advisory, so it keeps out other `withLock` calls and nothing else. To hold one longer than a block, such as for
  the whole run of an app, `lockFile.tryLock()` tries once and hands over a `FileLock`, or null when another
  holder has it; closing the `FileLock` releases it. On native Linux and Apple, a second spelling of one lock file
  through a dangling symlink or a hard link can pass the in-process check beside the first, and `fcntl` lets the
  same process through, so lock a plain file nothing links to. Coroutine code that should not block its thread while it
  waits uses `awaitLock` from [`fs-coroutines`](../fs-coroutines/README.md).
- **Walking.** `walkTopDown()` yields every regular file under a folder, and an error for anything it could not
  read. Each folder's own files come first, in name order, then its subfolders, also in name order. A folder
  reached through a symlink is not entered, though a link to a file is yielded; `walkTopDown(Symlinks.Follow)`
  enters linked folders, walking each real folder once under the first path that reaches it. A FIFO, device or
  socket inside a folder is skipped, and so is a symlink whose target is missing, since neither is a file to
  read; named as the folder to walk, a FIFO is `NotRegularFile`.

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

## A run's temp folder

`AppDirs.temp` from [`dirs`](../dirs/README.md) is this run's own scratch folder. Clear it with
`deleteRecursively()` on shutdown. `dirs.withRunTemp { temp -> runApp(temp) }` creates the folder and claims it
for as long as the block runs, by holding a lock on `.run.lock` inside it. `staleTempRuns()` then lists the
folders of this app's other runs that no live run holds, which is what a crash or a `kill -9` leaves, so a
cleanup can tell them from runs still going. That only works when every run claims its folder this way: one
whose run never did, or is starting at that very moment, counts as stale.

## Known limits

- **A full disk has no case of its own.** Every refusal the filesystem makes is one `WriteFailed`, carrying
  the platform's own message. `TooLarge` is typed because it is *this library's* ceiling, so "bigger than the
  cap you set" and "this machine is full" do not read alike, and it is the second that has no type. The JVM
  raises a bare `IOException` for `ENOSPC` with no subclass to match on, and its text comes from `strerror`, so
  a typed case built on that message would be dead on a machine that does not speak English. Read `reason`
  yourself where a full disk has to be told from anything else.
- **What kotlinx-io's `SystemFileSystem` cannot do.**
  - Nothing forces data to disk on its own, so an in-place write survives a crashed process but not a power cut.
    A rename write forces the temp file and then the folder to disk with `fsync` (`FileChannel.force` on the JVM,
    `FlushFileBuffers` on Windows). On macOS a plain `fsync` does not flush the drive's own cache, and
    `F_FULLFSYNC` is not used, so a power cut there can still lose a write that returned `Ok`.
  - A symlink whose target is missing cannot be resolved, so a write through one cannot create the folders its
    target needs and fails on the link instead; a `WriteMode.Atomic` write refuses such a link outright.
- **In-place writes are not atomic, and only one write may run on a file at a time, whether from other threads or
  other processes.** This is `WriteMode.InPlace`, and `WriteMode.Auto` whenever it falls back to writing in
  place. A rename write is atomic for readers, but two at once still race: the last rename wins and the other
  write is lost.
  - A process killed during a rename write leaves the file as it was or fully replaced, with at most an orphan
    `.<name>.<id>.tmp` beside it. That orphan holds a write that never landed, so it can be deleted;
    `leftoverBackups()` does not list it.
  - A process killed during an in-place write leaves the file half-written, with the backup `.<name>.<id>.bak`
    beside it, and `leftoverBackups()` finds it. The file itself may read perfectly, since a write cut short can
    leave text that still parses and says less than it did, so a leftover is a reason to check both copies rather
    than proof of which one is good: a process that died while the backup was still being copied leaves a backup
    shorter than the file. `<id>` identifies the write that made it, so a copy kept beside the file under a
    name of your own, such as `.app.toml.before-my-edit.bak`, is never counted as one.
  - An in-place write that creates the file has nothing to back up, so a process killed during it leaves the new
    file half-written with no backup, and `leftoverBackups()` has nothing to find.
  - Readers can see the file half-written while the write runs.
  - Two writes at once can leave a mix of both, and a failed write can undo another one that finished.
- **The temp file and the backup sit beside the file.** They need write permission on the folder, not only on the
  file. Their names are 42 bytes longer than the file's, so where names stop at 255 bytes a file named with more
  than 213 can only be created by an `InPlace` write, since a new file needs no backup, and every later write to it
  fails with `WriteFailed`. Where a platform has permissions both carry the file's own, before any content goes
  into them, so a config only you may read is never copied into one anybody may, and a restore leaves the file
  holding them. What a restore does not keep is the rest of the file's identity: the copy is a new file, so it has
  its own timestamps and inode, and other hard links to the original keep the half-written content. Windows has no
  such permissions, and what may read a file there comes from the folder it sits in: the native target copies the
  read-only flag, which is all a mode says there, and the JVM one leaves the copy to the folder.
- **Windows native.** Whether a rename there replaces an existing file in one step is unverified, so
  `WriteMode.Auto` writes an existing file in place and `WriteMode.Atomic` renames regardless. Ownership is not
  checked there, so an `Atomic` write can hand another user's file to you. kotlinx-io cannot follow symlinks
  there, so `Auto` writes through a link in place, `Atomic` refuses it, and a restore replaces a link with a
  regular file while the link's real target keeps the half-written content. Undoing a failed first write through
  a link whose target is missing can remove the link and leave the half-written file at its target. Its file calls
  use the ANSI Windows APIs, so a path with non-ASCII characters, such as a user name like `Jürgen`, likely fails.
  The JVM on Windows is unaffected.
- **Links on Windows.** Windows native counts every reparse point as a symlink, junctions included, while the JVM
  there asks `Files.isSymbolicLink`, which likely answers false for a junction, so a walk that skips links may
  enter a junction on the JVM. Neither has been verified on Windows.

## License

[Apache-2.0](../LICENSE).
