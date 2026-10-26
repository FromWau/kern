# Writer

Your app owns a few folders, and a stray name or a planted symlink should never let it write outside them. A `Writer`
writes, reads, moves, removes and locks files below one of those folders. No name you pass and no symlink in the
folder ever reaches outside it.

Use one for every folder your app owns. Use the plain `Path` calls for a path the user picked. A config that may be a
link into a dotfiles folder is read and written through `Path` calls, as
[Read a config only you may change](reading.md#read-a-config-only-you-may-change) shows.

Part of [`fs`](../README.md).

```kotlin
val data = dirs.data.writer().getOrElse { return warn(it) }
data.writeText("notes/today.md", text).onError { println("not saved: $it") }
```

## Rules

These hold for every call on a writer. The details at the end of this section cover the cases you rarely meet.

### The writer and its root

- `Path.writer()` makes the folder and each missing folder above it. It answers a `Writer` whose `root` is the
  folder's real path.
- With `create = false` it makes nothing, and a missing folder is `NotFound`.
- A writer holds nothing open and needs no closing. Keep it as long as you like.
- **The root is checked on every call.** A root moved, removed or replaced by another folder or a link is
  `FolderReplaced(root)`, and nothing is done. Make a new writer to write at the new folder.

### Names

- **A name is a relative path below the root**, such as `"logs/app.log"`. It is checked with
  [`relativePathProblem`](../README.md#check-a-relative-path-from-a-manifest) first.
- `"../x"`, `"/etc/passwd"`, `"a//b"` and `"bin/"` are `InvalidRelativePath(folder, entry, problem)`, and nothing is
  touched.
- A name over 255 bytes is `InvalidRelativePath` with `BadName(TooLong)`. A `Path` write answers `NameTooLong` there.
- A name shaped like a write's leftover, such as `.notes.md.<id>.tmp`, is `NameProblem.LeftoverName`. Calls that make
  an entry refuse it, so a leftover scan never mistakes your file for a crash's.
- Calls that read, move away or remove a leftover-shaped name take it. So does an append to one already there.
- Each refusal of a name or a link target is a `FileError.NameRefused`: `InvalidRelativePath`, `Escapes`,
  `InvalidName` or `NameTooLong`. One `is` check tells a name the writer will not make from a write that failed.
  Nothing was changed.

### Symlinks

- **A symlink on the way is followed only while it stays below the root.** A link that leads anywhere else is
  `Escapes(path, boundary, resolved)`, and nothing outside is touched.
- For a child writer, the boundary is that of the writer it was made from. For a stage, it is the stage itself, which
  its errors name as the folder it builds.
- A link whose path leaves the folder at any point is refused, even when it comes back in. In a writer on `state`,
  `a -> ../state/b` is `Escapes`, though a [walk](walking.md#rules) of `state` follows it.
- **A symlink at the name itself is never written through.** A write, `inPlace` included, refuses it as
  `NotRegularFile(path, Symlink)` and touches nothing. So does `openAppend`, each reopen included.
- With `replaceLink = true`, a write or `openAppend` puts a regular file in the link's place. A lock always does.
- `delete`, `deleteRecursively`, `move`, `readSymlink` and `info(name, followLinks = false)` act on the link itself.
  A read or `info` follows it under the rule above.

### Writes

- **A write renames a new file into place.** Readers never see half of it, and other hard links keep the old content.
- `inPlace = true` writes into the file itself. It keeps the file's inode, owner and hard links, with a backup beside
  it until the write is done.
- **A backup stops a write.** A killed in-place write leaves a `Backup` beside the file. Until you
  [restore or discard it](#deal-with-what-a-killed-write-left), a write of that file is `PendingBackup(path, backup)`
  and writes nothing.
- That check costs one listing of the folder per write.
- Temps and partial backups never stop a write, and the write leaves them where they are. A backup does not stop an
  append either.

### Modes

- **A shared writer makes folders `755` and new files `644`, or `755` when `executable`**, whatever the umask. The
  umask may narrow them but never widen them, so its group and everyone else never get to write what it makes new.
- That covers its root and the folders on the way, writes, `copyFrom`, appends, locks and stages.
- `keepAttributes` keeps the source's permission bits within `755`. That means no write for the group and everyone
  else, and no setuid, setgid or sticky.
- A shared writer's write by rename over a file already there gives the new file that file's own mode, so a `664`
  file stays `664`.
- A private writer's writes by rename are `600`, whatever mode the file had.

### Details

- On a dangling link, `writer()` and `privateWriter()` make the folder it leads to, with each missing folder above
  it. They keep the link, and that folder's real path is the `root`.
- With `create = false` a dangling link is `NotFound`. The same goes for `reader()`, which never makes anything.
- A writer's errors name paths below `root`, so compare one with `writer.root / name`, which is `dir.realPath()` and
  the name. Do not compare it with `dir / name`.
- `restore`'s errors name what it finds at a leftover's backup or file by the leftover's `copy` or `file`, as given.
  `discard`'s errors, which come through `delete`, and errors about a folder on the way to the backup or file can
  name paths by the real path of the folder they sit in.
- Two writes of one file at the same time race, and the last rename wins.
- While the first of two such writes is an in-place write still running, its backup sits beside the file, so the
  second may answer `PendingBackup`.

## Tasks

### Write a file, making the folders on the way

```kotlin
data.writeText("cache/index.json", json).onError { println("index not saved: $it") }
```

### Read one back, refusing one larger than a megabyte

```kotlin
val config = data.readText("app.toml", maxBytes = 1_048_576).orIfNotFound { "" }.getOrElse { return warn(it) }
```

### Keep a secret

```kotlin
val secrets = (dirs.state / "secrets").privateWriter().getOrElse { return warn(it) }
secrets.writeText("token", token).onError { println("token not saved: $it") }
val saved = secrets.readText("token").getOrElse { return warn(it) }
```

Keep secrets in a folder of their own below `state`. A log or any other shared writer that makes `state` makes it
`755`. `privateWriter()` on `state` itself refuses that as `OthersMayRead`. A `state` that `singleInstance` made first
is `700`, and a shared writer on it later keeps that.

`privateWriter()` makes these modes:

- Its root is `700`.
- Each missing folder above the root is made as a shared writer makes it: `755`, or narrower as the umask says.
- Each folder below the root is `700`, and each file `600`, lock files and appended files included.

It refuses a root another user owns, or one whose group or others may write or read. The refusal is
`Refused(path, info, why, asked)`, when the writer is made and on every call. `asked` is the path of the name the
call was given. A private writer reads only a file nobody else may change or read.

[Where a secret goes](writing.md#keep-a-secret) says why `state`, and how to fix a folder or file it refuses.

### Read without making anything

```kotlin
val data = dirs.data.reader().getOrElse { return warn(it) }
for (name in data.list("notes").getOrElse { return warn(it) }) println(name)
```

`reader()` answers a `Reader` with the writer's reads, under the same rules: `readText`, `readBytes`, `readPrefix`,
`readSymlink`, `realName`, `info`, `list` and `root`. It never makes anything, so a missing folder is `NotFound`. Use
it for a read-only command such as `status`. `privateReader()` reads as a private writer reads.

A `Writer` is a `Reader`, so code that only reads can take either. Say you hold a writer and want to hand it to such
code without letting it write. Pass `writer.reader`, a view with the same reads that cannot be cast back to a `Writer`.

### Import a file the user picked

```kotlin
data.copyFrom(picked, "imports/${picked.name}", keepAttributes = true).onError { println("not imported: $it") }
```

`keepAttributes` keeps the source's modification time. It keeps its permission bits within `755`, so the group and
everyone else never get to write the copy. A private writer's `600` file stays `600`.

### Append to a log another tool rotates

```kotlin
val state = dirs.state.writer().getOrElse { return warn(it) }
val log = state.openAppend("app.log", Reopen.WhenMoved()).getOrElse { return warn(it) }
log.use { it.appendLine("started") }
```

### Hold a lock around some work

```kotlin
val state = dirs.state.writer().getOrElse { return warn(it) }
state.withLock(".sync.lock") { syncNow() }.onError { println("not synced: $it") }
```

To wait by suspending instead, use `awaitLock` from [`fs-coroutines`](../../fs-coroutines/README.md).

### Build a folder whole

```kotlin
val installed: EmptyResult<FileError> = result {
    data.createDirectoryAtomically("packages/$version") { stage ->
        stage.writeText("manifest.toml", manifest).bind()
    }.bind()
}
```

Nobody sees the folder half built. A failed `bind()` removes the stage.

The stage is the stage writer's boundary. A link the build makes or follows must stay inside it, so one to `..` is
`Escapes`.

A build whose folder is replaced answers `FolderReplaced`. It leaves its stage in that folder, wherever it went. A
[leftovers](leftovers.md) scan of it finds a `StagedFolder`, which `discard` removes.

#### Details

The stage writer's `root` answers the real stage, `.<name>.<id>.dir` beside the folder. Its errors name each path as
it will be once placed, such as `packages/1.0/lib/up`, with the placed folder as an `Escapes` boundary. That holds for
the errors of an `AppendFile` or `FileLock` it opened, and of a leftovers scan, too.

- The stage path shows in a `FailedAt.Temp` where the error has an `at`. A `WriteFailed` or `RestoreFailed` with no
  spot of its own has the path in the stage there.
- `FolderReplaced` names the stage, its root.
- What you hand back to the stage writer keeps its path in the stage. That is the `Leftover` in a `PendingBackup`, the
  ones a scan finds, and the `path` of an `AppendFile` or `FileLock` it opened.

### Point a link at a new release in one step

```kotlin
data.switchSymlink("current", Path("releases/43")).onError { println("still on the old release: $it") }
```

### Tell a link that leads out from other failures

```kotlin
val config = dst.writer().getOrElse { return report(it) }
config.writeText("sub/app.toml", text).onError { failure ->
    when (failure) {
        is FileError.Escapes -> println("${failure.path} leads to ${failure.resolved}")
        else -> report(failure)
    }
}
```

`resolved` is where the link leads, and nothing was touched. To skip any name the writer will not make, check for
`FileError.NameRefused` instead, as
[Leave out a name a package may not make](folders-and-moves.md#leave-out-a-name-a-package-may-not-make) does.

### Use a folder that is a link to another place

```kotlin
val work = (notes.root / "work").writer(create = false).getOrElse { return warn(it) }
work.writeText("todo.md", text).onError { warn(it) }
val scan = work.leftovers().getOrElse { return warn(it) }
for (backup in scan.oldestBackups().values) work.restore(backup).onError { warn(it) }
```

- In a writer on `notes`, `work -> ../dotfiles/work` leads out, so every name below it is `Escapes`.
- A leftover scan of `notes` with `depth` 1 or more lists that link in `passedOver`.
- `writer(create = false)` on the link opens the folder it leads to. Its real path is the new writer's root and its
  boundary, so writes, scans and restores below it work there.
- A dangling link is `NotFound`, and nothing is made.

### Write a config kept in a dotfiles repo

```kotlin
val config = dirs.config.writer().getOrElse { return warn(it) }
config.writeText(name, text).onError { refused ->
    val linked = refused is FileError.Escapes ||
        refused is FileError.NotRegularFile && refused.type == FileType.Symlink
    if (!linked) return warn(refused)
    val path = config.root / name
    val leadsTo = path.realPath().getOrElse { missing ->
        // Nothing there yet: follow the links at the name, at most 40 as Linux does, to the first that is none.
        var placed = path
        var followed = 0
        while (followed++ < 40) {
            val target = placed.readSymlink().getOrNull() ?: break
            placed = if (target.isAbsolute) target else (placed.parent ?: Path(".")) / target
        }
        if (placed.readSymlink().getOrNull() != null) return warn(missing)
        val folder = (placed.parent ?: placed).realPath().getOrElse { return warn(it) }
        folder / placed.name
    }
    val repo = dotfiles.realPath().getOrElse { return warn(it) }
    if (leadsTo.relativeTo(repo) == null) return warn(FileError.Escapes(path, repo, leadsTo))
    path.writeText(text, WriteMode.Replace).onError { return warn(it) }
}
```

- A `Writer` never writes through a link. For `name` `app.toml`, `app.toml -> ~/dotfiles/app.toml` is
  `NotRegularFile(path, Symlink)`. For `nvim/init.lua`, a folder linked out, `nvim -> ~/dotfiles/nvim`, is `Escapes`.
- A link at the name may lead anywhere, since whoever made it chose its target, so the recipe follows each link at
  the name to where the file really is. For links to nothing yet, it follows them to where the file would be.
- It writes only when that place is inside `dotfiles`, the repository's folder, at the time of the check. Anywhere
  else is `Escapes`. A link changed between the check and the write is followed as it is then.
- The `Path` write follows the link and replaces the file it leads to, so the link stays, and the repository holds
  the new text. `WriteMode.Replace` renames, so a reader never sees half of it.
- Read it back through the `Path` too, as
  [Read a config only you may change](reading.md#read-a-config-only-you-may-change) shows.

### Hand part of the folder to other code

```kotlin
val plugin = data.writer("plugins/$id").getOrElse { return warn(it) }
```

A child writer has its own root. It keeps the boundary of the writer it was made from, so a link in it may lead up as
far as that boundary and no further. A child of a child keeps the same one. For a stage's child the boundary is the
stage, which its errors name as placed.

The parent makes the missing folders on the way to the child's root as it makes any folder. Under a shared writer
that is `755`, or narrower as the umask says. Under a private one it is `700`.

`data.privateWriter("accounts/alice/keys")` makes only `keys` `700`. Those are the same modes
`(dirs.data / "accounts/alice/keys").privateWriter()` gives.

### Deal with what a killed write left

```kotlin
val scan = data.leftovers(depth = Int.MAX_VALUE).getOrElse { return warn(it) }
val oldest = scan.oldestBackups(throughLinks = true)
for ((file, leftovers) in scan.byFile(throughLinks = true)) {
    val backup = oldest[file]
    if (backup != null) {
        data.restore(backup, throughLinks = true).onError {
            warn(it)
            continue
        }
    }
    for (leftover in leftovers.filter { it != backup }) {
        data.discard(leftover, throughLinks = true).onError { warn(it) }
    }
}
```

Each file gets its oldest backup back. A later backup may be a copy a later write made of a file an earlier crash had
already cut short. The file's other copies go only once that restore worked.

An `UnnamedTemp` names no file, so `byFile()` leaves it out. To ask before each restore, see
[Restore each file's oldest backup](leftovers.md#restore-each-files-oldest-backup).

`throughLinks = true` takes in what a link below the root led to. A config linked into a dotfiles folder keeps its
backup beside the link's target, and it is restored and discarded there.

## Reference

```kotlin
fun Path.writer(create: Boolean = true): Result<Writer, FileError>
fun Path.privateWriter(create: Boolean = true): Result<Writer, FileError>
fun Path.reader(): Result<Reader, FileError>
fun Path.privateReader(): Result<Reader, FileError>
```

The last column lists what a call answers besides the errors of the [Rules](#rules). The notes below the table say
when each one comes.

| call | what it does | answers besides the rules' errors |
|---|---|---|
| `writeText`, `writeBytes` | replaces the file by rename, or in place with `inPlace` | `NotRegularFile`, `PendingBackup`, `NameTooLong` |
| `copyFrom(source, name)` | copies a file from anywhere into the folder, as a write | `NotFound`, `NotRegularFile`, `Inaccessible`, then what a write answers |
| `readText`, `readBytes` | reads a file | `NotFound`, `NotRegularFile`, `TooLarge`, `Refused` |
| `readPrefix(name, maxBytes)` | reads up to `maxBytes` from the start of a file | `NotFound`, `NotRegularFile`, `Refused` |
| `info(name, followLinks = true)` | what is at the name | `NotFound` |
| `readSymlink(name)` | the target a link holds, as written | `NotASymlink`, `NotFound` |
| `realName(name)` | the name below the root of what is really at `name` | `Escapes`, `NotFound` |
| `list(folder = null)` | the names in a folder, or the root, in name order | `NotFound`, `NotADirectory` |
| `openAppend` | opens a file to append to | `NotRegularFile` |
| `delete` | removes a file, link or empty folder | `NotFound`, `WriteFailed` for a folder with entries |
| `deleteRecursively` | removes a tree, links as links, and answers `Ok` for nothing there | the first failure |
| `move(name, toName)` | moves within the folder, making the folders on the way | `NotFound`, `NotRegularFile`, `NotADirectory` |
| `createDirectories` | makes a folder and the levels on the way | `NotADirectory` |
| `switchSymlink(name, target)` | points a link at `target` in one step | `Escapes`, `InvalidName`, `NotRegularFile`, `AlreadyExists`, `NameTooLong` |
| `createDirectoryAtomically` | builds a folder through a stage writer, then renames it into place | `AlreadyExists`, `NameTooLong` |
| `tryLock`, `withLock` | the lock [Locking](locking.md) describes | `LockBusy`, `NotRegularFile`, `LockFailed` |
| `writer(name)`, `privateWriter(name)` | a writer on a folder below the root, made with the missing folders on the way, within this one's bounds | `NotADirectory`, `Refused` |
| `leftovers` | what killed writes left below the root, as [Leftovers](leftovers.md) lists them | what listing answers |
| `discard`, `restore` | removes a leftover or puts a backup back, through the writer | `Escapes`, `Refused`, `LockBusy` |

### Notes on the calls

**`writeText`, `writeBytes`:**

- `executable` adds execute bits. `replaceLink` puts the file in place of a link at the name. `modifiedAt` gives the
  file that modification time.
- `NotRegularFile` is for a folder, a link without `replaceLink`, or another non-file.
- `PendingBackup` is for a backup beside the file.
- `NameTooLong` is for an in-place name too long for its backup.

**`copyFrom`:**

- `modifiedAt` gives the copy that time, in place of the one `keepAttributes` keeps.
- `NotFound`, `NotRegularFile` or `Inaccessible` name `source`, and come before anything is touched. After that it
  answers what a write answers.

**`readText`, `readBytes`, `readPrefix`:**

- `trusted` makes `readText` and `readBytes` refuse a file others may change.
- `streams = true` makes `readText` and `readBytes` read a FIFO or a character device too, as
  [`Path.readText`](reading.md#rules) reads it.
- `Refused` comes with `trusted`, or for a private writer or reader. For `readPrefix` it comes only for a private
  writer or reader.

**`info`, `readSymlink`, `realName`, `list`:**

- `info` with `followLinks = false` describes a link itself, as `Symlink`.
- `readSymlink` answers the target as written. `realName` answers where it leads.
- `realName` follows every link on the way and at `name`, as a read follows it. It answers `releases/43` for
  `current -> releases/43`, and `name` itself where no link is.
- `realName` answers null for the root. Only links ending there give that, such as `self -> .` at the root.
- `realName` answers `Escapes` for a link leading out, and `NotFound` for a missing target.
- `list` answers names the other calls take: `pkgs/a`, or a bare `a` at the root. It lists links and leftovers as
  they are.

**`openAppend`, `move`:**

- `openAppend` with `replaceLink` puts a new file in place of a link at the name. Its `NotRegularFile` is for a
  folder, a link without `replaceLink`, or another non-file.
- `move` answers `NotRegularFile` for a file onto a folder, and `NotADirectory` for a folder onto a file.

**`switchSymlink`:**

- A relative `target` is read from the link's folder. By its names it may lead out of the root at no step, as the
  rules say of a link. It need not exist.
- It is judged by its names alone, so a `target` that is itself a link leading out is switched to, and reads through
  the new link answer `Escapes`.
- `Escapes(path, boundary, resolved)` is for a `target` that steps out, or an absolute one not below the root.
  `resolved` is the target joined to the link's folder.
- `InvalidName(path, LinksToItself)` is for a target naming the link itself.
- `NotRegularFile` is for a folder at the name, and `AlreadyExists` for a file.
- `NameTooLong` is for a name over `MAX_STAGED_NAME_BYTES`.

**`createDirectoryAtomically`:**

- It makes the missing folders above the new folder.
- `AlreadyExists` is for anything already at the name, a link included. The block is then never called.
- It is also `AlreadyExists` for anything that appears at the name while the block runs.
- `NameTooLong` is for a name over `MAX_STAGED_NAME_BYTES`.

**`discard`, `restore`:**

- With `throughLinks = true` they also take a leftover found through a link below the root, in the folder that link
  leads to.
- `Escapes` is for a leftover outside the root, by the real path of its folder, unless `throughLinks` takes it.
- `Refused` is for that folder when another user owns it or others may write it.
- `discard` answers `LockBusy` for a build still running.

### Permissions and platforms

- `Refused`'s `why` is `RefusedBecause.OtherOwner`, `OthersMayWrite`, or `OthersMayRead` for a private writer's root
  or a file it reads.
- A shared writer reads with `trusted = true` as a private one reads, but takes what others may read. On every call
  it checks the file, the root and each folder on the way. For a child writer it checks each folder up to its
  boundary.
- Windows keeps no such permissions, so there a private writer is a shared one.
- Linux and iOS open each folder on the way one at a time without following a link, so a folder swapped for a link
  while a call runs is never entered.
- The JVM, Android, macOS and Windows reach each folder by its path and check it just before use, so a folder swapped
  for a link in that moment is followed.
- On Windows a root replaced by another folder may go unnoticed. The JVM there cannot tell one folder from another,
  and Windows native is unverified.
