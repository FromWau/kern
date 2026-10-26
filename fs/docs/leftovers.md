# Leftovers

A write that is killed halfway leaves a copy beside its file. So does a write that could not finish cleaning up, and a
folder build cut short. This page shows how to find those copies and how to restore or discard them through a
[Writer](writer.md). Look for leftovers at startup, before the first write.

Part of [`fs`](../README.md).

```kotlin
fun Path.leftovers(): Result<List<Leftover>, FileError>
fun Path.leftoversIn(depth: Int = 0): Result<LeftoverScan, FileError>
fun AppDirs.leftovers(): Result<LeftoverScan, FileError>
data class LeftoverScan(
    val found: List<Leftover>,
    val throughLinks: List<Leftover>,
    val failed: List<ScanFailure>,
    val passedOver: List<Path>,
) {
    fun byFile(throughLinks: Boolean = false): Map<Path, List<Leftover>>
    fun oldestBackups(throughLinks: Boolean = false): Map<Path, Leftover.Backup>
}
fun Writer.leftovers(depth: Int = 0): Result<LeftoverScan, FileError>
fun Writer.discard(leftover: Leftover, throughLinks: Boolean = false): EmptyResult<FileError>
fun Writer.restore(backup: Leftover.Backup, throughLinks: Boolean = false): EmptyResult<FileError>
```

## Rules

- A write cleans up its own copies when it can, so a leftover means a process was killed during a write, or a write
  could not finish cleaning up.
- A write leaves earlier writes' leftovers where they are. A `Backup` is the one that gets in the way.
- A write or copy of a file with a `Backup` beside it is `PendingBackup` until that backup is restored or discarded.
  That goes for a [Writer](writer.md#rules) and a `Path` alike.
- A write through a link looks beside the link's target. An append is never stopped.
- Only names that carry a write's id are listed.
- The library deletes none of them itself. The one exception is `mirrorTo`'s stale removal.
- `list()` and `walkTopDown()` yield leftovers like any other entry.
- A `Writer` refuses such a name when you give it to a call that makes an entry.
- `file.leftovers()` lists what writes of `file` left beside it.
- `folder.leftoversIn()` lists what writes of any file left in the folder, as the `found` of a `LeftoverScan`.
- `fileMayBeDamaged` is true only for a `Backup`. Every other kind left its file as it was.
- `writer.discard(leftover)` deletes the copy. `writer.restore(backup)` puts a backup back.
- Both go through the writer. A copy or file outside its root, by the real path of its folder, is `Escapes`. One
  already gone is `NotFound`.
- A `discard` that answers `LockBusy` means that build is still running. Skip it.

| `Leftover` | name | what it means | what to do |
|---|---|---|---|
| `Temp` | `.<name>.<id>.tmp` | new content a rename write never renamed over the file | discard |
| `UnnamedTemp` | `.<id>.tmp` | the temp of a rename write whose name was too long | discard |
| `Backup` | `.<name>.<id>.bak` | the whole old content an in-place write copied | check, restore or discard |
| `PartialBackup` | `.<name>.<id>.bak.part` | a backup still being copied | discard |
| `StagedFolder` | `.<name>.<id>.dir` | the stage of a [`createDirectoryAtomically`](folders-and-moves.md#build-a-folder-whole) that never placed it | discard |
| `StagedLink` | `.<name>.<id>.link` | the new link of a [`switchSymlinkTo`](folders-and-moves.md#make-and-read-a-symlink) never moved into place | discard |

- After a `Temp`, the file is as it was.
- An `UnnamedTemp` is used when the name had no room for `.<name>.<id>.tmp`. It names no file.
- After a `Backup`, the file may be half-written.
- After a `PartialBackup`, the file was not written yet.

### Details

- Each leftover names its `copy`, its `file`, `startedAt` and `via`. `file` is null only for an `UnnamedTemp`.
- `via` is the symlink that leads to the folder the copy lies in. That is a link to a file at the name you gave. Or
  else it is the folder the name sits in, when that folder is a link.
- For a scan, `via` is the scanned folder when that folder is a link.
- A scan, a `PendingBackup` and `leftovers()` agree on `via`. It is null otherwise. That includes a link further up,
  and a folder below a scanned link.
- A `Writer` write never goes through a link at the name, so only its folder counts.
- With `throughLinks = true`, a copy outside the root can still be acted on, in the folder it sits in. Its `via` must
  lie below the root and be a link leading to that folder.
- That folder is `Refused` when another user owns it or others may write it.
- A copy whose `via` is not below the root, or leads elsewhere, stays `Escapes`.

## Tasks

### Scan a folder

```kotlin
val scan = dirs.config.leftoversIn().orIfNotFound { LeftoverScan.EMPTY }.getOrElse { return report(it) }
scan.failed.forEach { report(it.error) }
(scan.found + scan.throughLinks).forEach { println("left over: ${it.copy}") }
```

`throughLinks` holds what symlinks in the folder led to, outside it. A folder not made yet is `NotFound`.

### Scan the whole app

```kotlin
val everywhere = dirs.leftovers().getOrElse { return report(it) }
everywhere.found.forEach { println("left over: ${it.copy}") }
```

This scans config, data, state and runtime recursively. It skips `cache`.

### Scan a package cache

```kotlin
val packages = (dirs.cache / "packages").writer().getOrElse { return report(it) }
val locks = (dirs.cache / "locks").writer().getOrElse { return report(it) }
val scan = packages.leftovers(depth = 1).getOrElse { return report(it) }
for (staged in scan.found) {
    val discarded: EmptyResult<FileError> = when (staged) {
        is Leftover.StagedFolder -> packages.discard(staged)
        is Leftover.StagedLink -> result {
            locks.withLock("${staged.file.parent?.name}/current.lock") { packages.discard(staged).bind() }.bind()
        }
        else -> continue
    }
    discarded.onError { if (it !is FileError.LockBusy) report(it) }
}
```

- The cache is laid out as
  [Make a second install wait for the first](folders-and-moves.md#make-a-second-install-wait-for-the-first) lays it out.
  There is one folder per package, `packages / name`, with its versions and its `current` link in it.
- A killed build's stage, and a killed switch's staged link, sit beside them.
- `depth = 1` reads `packages` and each package folder. It reads none of the installed files.
- A stage whose `discard` is `LockBusy` belongs to a build still running, so it stays.
- A staged link is discarded while holding the lock [`useVersion`](folders-and-moves.md#switch-the-version-in-use)
  takes, so the link of a switch still running stays: that lock is then `LockBusy`.

### Restore each file's oldest backup

```kotlin
val config = dirs.config.writer().getOrElse { return report(it) }
val scan = config.leftovers().getOrElse { return report(it) }
for ((file, leftovers) in scan.byFile()) {
    val backups = leftovers.filterIsInstance<Leftover.Backup>()
    val oldest = backups.firstOrNull() ?: continue
    if (!confirm("$file may be cut short; restore it?")) continue
    config.restore(oldest).onError {
        report(it)
        continue
    }
    for (backup in backups.drop(1)) config.discard(backup).onError { report(it) }
}
```

Restore from the oldest backup. A later write may have copied a file that an earlier crash had already cut short.
Discard the later backups only once the oldest is back. That way a declined or failed restore leaves every backup in
place.

### Restore what may be damaged, discard the rest

```kotlin
val notes = (dirs.data / "notes").writer().getOrElse { return report(it) }
val scan = notes.leftovers().getOrElse { return report(it) }
val byFile = scan.byFile(throughLinks = true)
for ((file, oldest) in scan.oldestBackups(throughLinks = true)) {
    if (!confirm("restore $file?")) continue
    notes.restore(oldest, throughLinks = true).onError {
        report(it)
        continue
    }
    val later = byFile.getValue(file).filterIsInstance<Leftover.Backup>().drop(1)
    for (backup in later) notes.discard(backup, throughLinks = true).onError { report(it) }
}
for (leftover in (scan.found + scan.throughLinks).filterNot { it.fileMayBeDamaged }) {
    notes.discard(leftover, throughLinks = true).onError { report(it) }
}
```

- `oldestBackups()` keys a file the way `byFile()` does, so `getValue(file)` finds the copies of each file it names.
- With `throughLinks = true`, a note can be a link into a dotfiles folder. Its backup is restored there, and the
  copies beside it are discarded there.
- Every leftover that is not a `Backup` left its file as it was, so it goes, whether or not a restore was declined.
- A declined or failed restore keeps all of that file's backups, so a write of that file stays `PendingBackup`,
  through a `Writer` or a `Path`.
- A folder in `notes` that is a link to another place is never scanned. A scan with `depth` 1 or more lists it in
  `passedOver`.
- Give such a folder a writer of its own, `link.writer(create = false)`, and scan and restore through that. [Use a
  folder that is a link to another place](writer.md#use-a-folder-that-is-a-link-to-another-place) does this.

### Recover at a daemon's startup

```kotlin
fun Path.real(): Path = realPath().getOrElse { this }
val mine = setOf((dirs.state / "db" / "index.json").real())
val instance = dirs.singleInstance("daemon").getOrElse { return report(it) }
instance.use {
    dirs.clearStaleTempRuns().fold(
        onSuccess = { cleanup -> cleanup.failed.forEach { report(it.error) } },
        onError = { report(it) },
    )
    val state = dirs.state.privateWriter().getOrElse { return report(it) }
    val scan = state.leftovers(depth = Int.MAX_VALUE).getOrElse { failure ->
        report(failure)
        LeftoverScan.EMPTY
    }
    scan.failed.forEach { report(it.error) }
    val oldest = scan.oldestBackups(throughLinks = true).values.filter { it.file.real() in mine }
    for (backup in oldest) state.restore(backup, throughLinks = true).onError { report(it) }
    (scan.throughLinks - oldest.toSet()).forEach { println("outside state, check it by hand: ${it.copy}") }
    // Only an in-place write leaves a Backup, so one beside a file outside `mine` is not the daemon's.
    for (leftover in scan.found - oldest.toSet()) {
        state.discard(leftover).onError { if (it !is FileError.NotFound) report(it) }
    }
    serve()
}
```

- Holding `singleInstance` first means no other run of the daemon can be writing in `state`.
- The recipe has no shared writer on `state`, so it opens it with `privateWriter()`.
- A `state` that others may read passes `singleInstance`, but `privateWriter()` stops it as `OthersMayRead`. A
  `chmod 700` of `state` fixes it, as [Keep a secret](writing.md#keep-a-secret) fixes a `secrets` folder.
- `mine` is the set of files the daemon writes in place, by real path. That tells its own backups apart wherever the
  scan found them, a symlinked file included. Only those are restored.
- `throughLinks = true` restores the backup of a file of `mine` that is a link out of `state`. The restore happens in
  the folder the file really sits in, and the link stays a link.
- Anything else the scan found through a link is printed for a check by hand.
- The daemon writes every other file through the `Writer` by rename, or appends to it. Neither leaves a `Backup`.
- So a backup beside such a file, such as an append-only log, came from elsewhere and is discarded.
- A backup left in place stops writes of that file, through the `Writer` or a `Path`, as the [rules](#rules) say.
- A folder in `state` that is a link to another place lands in `scan.passedOver`. It gets a writer and a scan of its
  own, as [Use a folder that is a link to another place](writer.md#use-a-folder-that-is-a-link-to-another-place) shows.
- An app may keep a shared writer on `state`, for example for a log others may read. It then scans, restores and
  discards with that writer instead of `privateWriter()`.
- That shared writer reaches a private folder below too, such as a `secrets` folder only you may open. A restored file
  there keeps its `600`.
- An oldest backup whose restore fails stays for the next start, since it holds the most of that file.

## Reference

You do not need this part on a first read. Come back when a scan finds something you did not expect.

### Kinds

- `startedAt` is when the write started, to the millisecond. It comes from the time-ordered id in the copy's name.
- `startedAt` is null when that id is a UUID of another version than 7.
- `found` lists the oldest write first, and one write's copies in name order. Those with no `startedAt` come last, by
  name.
- A file whose first rename write crashed has only a temp, so `leftovers()` works on a file that does not exist.
- An `UnnamedTemp` is found only by a scan of the folder, never by `leftovers()` of one file.
- `restore(backup)` puts the backup back into the file itself, in place. The file keeps its inode, owner, links and
  permissions.
- Then `restore` deletes the backup and answers `Ok(Unit)`. On a failure the backup stays.
- A missing file is created with the backup's permissions before any content goes in.
- With `throughLinks = true` the file is the one beside the backup, which the link leads to, so a symlink at the name
  in the root stays a link.
- A build still running is listed as a `StagedFolder` too. It holds a claim `.<name>.<id>.lock` beside it.
- `discard` takes that claim first. If it is held, `discard` is `LockBusy` naming the claim, with the holder's pid
  where the platform can tell, and nothing is deleted.
- If the claim is free, `discard` removes the stage with all it holds, and the claim file.
- A claim file is never listed on its own, so one left with no stage beside it stays, since a build that just started
  holds its claim before its stage exists.
- A `StagedLink` has nothing to restore. `discard` removes the staged link, never what it leads to.
- A switch holds no claim on its staged link, so discard one only when no switch of that link can be running.
- Discard a `Temp` or `PartialBackup` only once no other write to that file can be running, for example while you hold
  the app's lock. A write in progress has one too.

### Discarding

- A listing meant to show the user's files should skip each copy `leftoversIn()` reports.
- A symlink whose name looks like a leftover is listed as one. `discard` removes only the link.
- Restore only leftovers of files your app writes. A folder shared with another program can hold its copies.
- Do not scan or discard leftovers in a mirror's `dst` yourself. `mirrorTo` removes them, temps and backups alike,
  before it copies anything, as the [mirror rules](walking.md#mirror-rules) say.

### Scanning

- `leftovers()` of a file that cannot be looked up, such as a symlink loop, is `Inaccessible` naming it.
- For a file that is not there, `leftovers()` still reads the folder. An error reading it names the folder.
- Found through a link, a leftover's `file` names the link's target and `via` names the link.
- Through a dangling link, `file` is spelled from the link's folder and may hold `..`. Show it with `canonicalPath()`.
- `leftoversIn()` reads one folder. It is `NotADirectory` for a path that is not one.
- It asks each symlink in the folder for its `leftovers()`, a dangling one included. What that finds goes in
  `throughLinks`, in the same order.
- A copy found both ways is listed once, in `found`. A copy that two links lead to is listed once, through the first.
- A link whose target cannot be checked goes into `failed` as a `ScanFailure` naming it and its error. So does an entry
  whose type cannot be looked up. The scan goes on.
- An entry gone by then is passed over.
- The call's own error is only for the folder itself. `orIfNotFound { LeftoverScan.EMPTY }` turns a missing folder into
  a scan that found nothing.
- `depth` is how many levels of folders below are read too. They are merged into one scan in the same order.
- `0`, the default, reads the folder alone. `1` reads it and each folder directly in it. `Int.MAX_VALUE` reads every
  folder below.
- A negative `depth` throws `IllegalArgumentException`.
- A folder below that cannot be listed goes into `failed`. One gone by then is passed over.
- A scan lists a staged folder once and never looks inside it.
- A link to a folder is never entered. If the scan would have entered it were it a folder, it is listed in
  `passedOver`, wherever it leads. That includes a link that stays inside the root.
- So a `depth = 1` scan lists those links in the folder it scans, not those a level below. To cover them, walk with
  `walkTopDown()` and scan the root and each `WalkEntry.Folder` with `leftoversIn()`.
- `dirs.leftovers()` passes over a folder that does not exist. It reports a folder that is two of them only once, such
  as config and data on macOS.
- Scan `cache` with `dirs.cache.leftoversIn(depth = Int.MAX_VALUE)`.
- For a cache of installed trees, scan with `depth = 1`, as [Scan a package cache](#scan-a-package-cache) does. A
  killed build's stage sits beside the versions, and a deeper scan would read every installed file.

### Grouping

- `scan.byFile()` groups what the scan found by the file each copy was for, oldest first. It leaves out an
  `UnnamedTemp`.
- `byFile(throughLinks = true)` takes in `throughLinks` too and matches files by `realPath()`, so a file reached both
  directly and through a link is one entry, keyed by the `file` of its oldest copy.
- A later backup beside a link's own name still lands in that entry, even though `leftovers()` of its target does not
  look there.
- A `StagedFolder` or `StagedLink` is matched by its `file` with only its folder resolved, so a staged link and the
  file the link leads to are two entries.
- `scan.oldestBackups()` names the first `Backup` for each file that has one: the earliest `startedAt`, then the first
  by name. It groups and keys as `byFile()` does.
- `oldestBackups()` lists exactly the files that may be damaged, each with the backup to restore. Every leftover that
  is not a `Backup` left its file as it was.
  [Restore what may be damaged, discard the rest](#restore-what-may-be-damaged-discard-the-rest) builds on this.
