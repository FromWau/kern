# Writing

Saving a file goes wrong in quiet ways. The app is killed halfway, the file is a link, or a hard link shares it.
These calls replace a file's content by rename or in place, and copy a file. Each one tells you how it went.

This page is for a path the user picked. For your app's own folders, write through a [Writer](writer.md). A Writer
never writes through a link.

Part of [`fs`](../README.md).

```kotlin
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
```

## Rules

### What a write answers

- A write answers `Written(way, info)`.
- `way` says how it replaced the file: `WriteWay.Renamed` or `WriteWay.InPlace`.
- `info` is the `FileInfo` of the file it left. It is read once the content and time are in, and for a rename once
  the file is renamed.
- Linux, Apple and Windows read that info from the written file's own descriptor or handle, so a file another process
  puts at the path afterwards is never the one it describes.
- The JVM and Android look the path up by name right after the write.

### By rename

- The content goes into a temp file `.<name>.<id>.tmp` beside the file. It is forced to disk, then renamed over the
  file.
- Readers see the old content or the new. A failure leaves the file as it was.
- A renamed file is a new file. It takes the old one's permissions and group, and it belongs to you.
- It starts with fresh timestamps, ACLs and extended attributes. Other hard links keep the old content.
- A new file is written by rename in every mode but `InPlace`.

### In place

- The old content is first copied to the backup `.<name>.<id>.bak`. Then the file itself is written, so its inode,
  owner and links stay.
- A failed write copies the backup back. If that fails too, the answer is `RestoreFailed`.

### Permissions and time

- A new file gets the permissions the umask gives, such as `644`. An existing file keeps its own.
- `ownerOnly = true` leaves the file `600` before any content goes in. Each missing folder made on the way is `700`.
- `executable = true` adds an execute bit for each class that may read the file. `false` leaves the mode alone.
- `modifiedAt` gives the file that modification time. By rename, the new file gets it before it takes the name. In
  place, it is set once the content is written.
- Windows ignores `ownerOnly` and `executable`.

### Links and other files

- **A link at the path is followed.** The file it points at is replaced, and the link stays a link.
- A missing target is created with its folders. A relative link is read from the link's folder, so a dotfile a
  manager linked is written in its repository.
- So a write through a dangling link creates its target and the missing folders on the way, wherever it leads.
- Where you do not want that, check `fileType(followLinks = false)` first. It answers `Symlink` for any link.
- `Path.writer()` on a dangling link makes the folder it leads to the same way.
- Anything at the path that is not a regular file is `NotRegularFile`, as for a read.

### A backup a killed write left

- A killed in-place write can leave its backup beside the file. For a write through a link, it sits beside the
  link's target.
- That backup stops every write of the file, a copy onto it included, as `PendingBackup` until you restore or
  discard it, just as a [Writer](writer.md) write is stopped.
- An append is never stopped. [Leftovers](leftovers.md#rules) has the rule.
- To find that backup, each write and copy lists the target's folder once. That costs more in a folder of many files.
- A folder it cannot list lets the write go ahead.

### Write modes

This is what each `WriteMode` does to a file that already exists:

| `mode` | readers can see it half-written | other hard links | owner | group | permissions |
|---|---|---|---|---|---|
| `Auto`, the default | only when it writes in place | kept: it writes in place | kept: it writes in place | kept: it writes in place | kept |
| `Atomic` | never | kept: refused with `AtomicRefused(OtherHardLinks)` | kept: refused with `OtherOwner` | kept: refused with `GroupNotKept` | kept |
| `Replace` | never | cut off, keeping the old content | becomes you | kept when it can be | kept, see below |
| `InPlace` | yes | kept | kept | kept | kept |

- `Replace` keeps the permissions, less the group's when it cannot keep the group, so `660` becomes `600`.

To pick one:

- `Auto` renames a file of yours with no other hard links. It writes any other file in place.
- Use `Replace` when a write must never go through a hard link into the content another name shows.
- `Replace` already is the policy "never half a file, cut hard links if needed", so you need no `Atomic` write before
  it. Pair the two only to learn that links were cut, as
  [Find out whether a write cut other hard links off](#find-out-whether-a-write-cut-other-hard-links-off) does.
- Use `Atomic` when a half-written file must never be seen and a refusal is fine.
- Use `InPlace` to keep the file's inode, owner and hard links.

## Tasks

### Pick the write for a user's own file

The right call depends on what is at the path and what you want to keep.

| the file at the path | to keep | write with |
|---|---|---|
| a symlink, such as a dotfile a manager linked | the link, writing the file it leads to | a `Path` write, which follows the link |
| a symlink | everything it leads to as it is | a [Writer](writer.md#rules) |
| one with other hard links | the content every name shows the same | `InPlace`, or `Auto`, which writes it in place |
| one with other hard links | never a half-written file, links cut | `Replace` |
| another user's | its owner | `InPlace` or `Auto` |
| a mode such as `664` | its mode | a `Path` write, or a shared `Writer` |

- A `Writer` answers a link with `NotRegularFile(path, Symlink)`. With `replaceLink = true` it puts a file in its
  place.
- A `Writer` write cuts other hard links too.
- For another user's file, `Atomic` refuses it as `OtherOwner`, and `Replace` makes it yours.
- A `Path` write with `Replace` drops the group's bits when it cannot keep the group.
- A private `Writer` makes the file `600`.

### Know whether readers could see a write half-written

`way` tells you which way the write went.

```kotlin
when (settings.writeText(text).getOrElse { return report(it) }.way) {
    WriteWay.Renamed -> Unit
    WriteWay.InPlace -> println("$settings was written in place, so readers may have seen it half-written")
}
```

### Keep a secret

```kotlin
val secrets = (dirs.state / "secrets").privateWriter().getOrElse { return report(it) }
secrets.writeText("token", token).onError { return report(it) }
```

Keep a token, key or password in `dirs.state`: a config folder often lives in a dotfile repo, and `dirs.data` is the
roaming `%APPDATA%` on Windows and the config folder on macOS.

A [`privateWriter()`](writer.md#keep-a-secret) writes the secret `600`. It makes its folders `700`, its root and those
below it. It refuses a link at its name as `NotRegularFile(path, Symlink)` and writes nothing through it.

Give it a `secrets` folder of its own in `state`. A log or any other shared writer that makes `state` makes it `755`.
`privateWriter()` on `state` itself refuses that as `OthersMayRead`. A `state` that `singleInstance` made first is
`700` and stays so.

When the folder is too open:

- A `secrets` folder its group or others may read is `Refused(path, info, OthersMayRead, asked)`.
- So is a file there that they may read, when the writer reads it.
- Fix the folder with `chmod 700` and the file with `chmod 600`, then call again.
- A folder others may write is `OthersMayWrite`. The same `chmod 700` fixes it.

### Write a script that runs

```kotlin
(dirs.data / "hook.sh").writeText("#!/bin/sh\nexec tool sync\n", executable = true).onError { return report(it) }
```

### Find out whether a write cut other hard links off

Try `Atomic` first. If it refuses because of other hard links, write with `Replace` and say so.

```kotlin
val atomic = settings.writeText(text, WriteMode.Atomic)
val refusal = (atomic.errorOrNull() as? FileError.AtomicRefused)?.why
if (refusal == AtomicRefusal.OtherHardLinks) {
    settings.writeText(text, WriteMode.Replace).onError { return report(it) }
    println("$settings had other hard links, which keep the old content")
} else {
    atomic.onError { return report(it) }
}
```

### Copy a file

```kotlin
when (script.copyTo(backup / script.name, keepAttributes = true).getOrElse { return report(it) }) {
    is Written -> println("backed up $script")
    is Copied.Unchanged -> println("$script is its own backup already")
}

// Never writes through a link someone left at app.toml.
config.copyFrom(source, "app.toml").onError { return report(it) }
```

`copyTo` writes like `writeBytes`. It takes the same `mode`, gives the same errors and streams the content.

`keepAttributes = true` also gives the copy the source's permissions and modification time. `modifiedAt` gives it the
time you name instead, as for a write.

## Reference

You do not need this part on a first read. Come back when a write leaves a name you did not expect, or a mode does
something you did not expect.

**Names.** `<id>` is a time-ordered UUID, version 7.

- A name over 213 bytes in UTF-8 has no room for `.<name>.<id>.tmp`. Its temp is then `.<id>.tmp`, which
  [Leftovers](leftovers.md) finds as a `Leftover.UnnamedTemp`.
- Every name up to 255 bytes writes by rename.
- The backup is first copied to `.<name>.<id>.bak.part`, 47 bytes longer than the name, so an existing file is
  written in place only with a name up to 208 bytes.
- A longer one is `NameTooLong(path, LongName.PartialBackup, bytes)` before anything is created. A new file needs no
  backup.
- `Auto` with a longer name renames. When it has to fall back to writing in place, it deletes its temp and answers
  that `NameTooLong`. Nothing is left behind.
- After an in-place write, done or undone, the write tries to delete the backup. One that cannot be deleted stays.

**`ownerOnly`:**

- A rename write's temp file is `600` rather than the old file's mode.
- An in-place write narrows the file before emptying it, and its backup is `600` too.
- In place it narrows the file itself, so every hard link to it is narrowed. A file you do not own is `WriteFailed`.
- A refused write, such as `AtomicRefused` or `NameTooLong`, changes nothing, so the file keeps its mode.
- For a file that must end up `600`, write with `WriteMode.Replace`. It never answers `AtomicRefused`.
- It applies on Linux, Apple, Android, and the JVM on a file system with POSIX permissions.
- Missing folders are made as [Creating folders](folders-and-moves.md#create-folders) describes.

**`executable`:**

- It is set at the same moment as `ownerOnly`, before any content goes in.
- A new file is `755` under umask `022`, and `700` with `ownerOnly`.
- A rename write's temp gets the bits. An in-place write adds them to the file itself, so every hard link sees them.
  There a file you do not own is `WriteFailed`.
- With `copyTo(target, keepAttributes = true)`, the bits are added to the mode carried over from the source.

**`Auto` and `Atomic`:**

- `Auto` renames only a file that has no other hard links, belongs to you, and whose group the new file can be given.
- Otherwise it writes in place. It also writes in place when it cannot read or keep the file's links, owner or group.
- `Atomic` refuses with `AtomicRefused` only a file it knows a rename would change. It renames one whose links or
  owner it cannot read.
- The JVM on Windows reads no link count or uid. There `Auto` writes an existing file in place, and `Atomic` renames
  it.
- `Auto` writes a file with other hard links in place, so a crash leaves the content every link shows half-written.
- Where that matters, use `Atomic`, `Replace` or a [Writer](writer.md), which always renames.
- A file that cannot be read or written is refused in every mode. So is a folder that cannot be written.
- `NotRegularFile` keeps a write from opening a FIFO, which waits on a reader that may never come. It also keeps a
  write from replacing a device node with a regular file.
- [Known limits](../README.md#known-limits) has what the modes cannot promise, such as two writes at once.

**Links.** The temp file and the backup sit beside the link's target, not beside the link.
[`leftovers()`](leftovers.md) looks there.

**Copying:**

- The source must be a regular file you may read. Otherwise the answer is `NotFound`, `NotRegularFile` or
  `Inaccessible` naming the source, and `target` is left alone.
- Only the content is copied. An existing `target` keeps its permissions, and a new one gets the umask's.
- So a `600` source copied to a new `target` gives `644` under `022`, unless `ownerOnly = true` leaves it `600`.
- `keepAttributes = true` sets the source's permissions before any content goes in. It sets the modification time
  once the content is in, to the precision both file systems keep. `ownerOnly = true` still leaves the copy `600`.
- `modifiedAt` gives the copy that time in place of the one `keepAttributes` keeps. It is set as a write sets it,
  with or without `keepAttributes`, so a copy can carry its source's time without its permissions.
- Windows keeps the time. Of the permissions, Windows native carries only the read-only flag.
- A `target` that is the source itself is left as it is. That covers another name, a hard link and a symlink. The
  answer is `Copied.Unchanged(info)`, with the file's info.
- Like a write, `copyTo` follows a link at `target`. `Writer.copyFrom` puts the copy in the link's place instead, also
  when the link leads to the source itself.
