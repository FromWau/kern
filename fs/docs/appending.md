# Appending

A log or a CSV grows one line at a time. You want each line to land whole at the end, even with two processes
writing to one local file. And when another tool rotates the log, you want the next line in the new file, not the
old one.

These calls add to the end of a file one write at a time. For a log in your app's own folder, open it through a
[Writer](writer.md).

Part of [`fs`](../README.md).

```kotlin
fun Path.openAppend(
    reopen: Reopen = Reopen.Never,
    ownerOnly: Boolean = false,
): Result<AppendFile, FileError>
fun Writer.openAppend(
    name: String,
    reopen: Reopen = Reopen.Never,
    replaceLink: Boolean = false,
): Result<AppendFile, FileError>
```

## Rules

- `openAppend()` creates the file and its missing folders. It answers an `AppendFile`, which you `close()` or wrap in
  `use { }`.
- Each `append(bytes)` lands at the end in one write, so lines two processes append to one local file never tear.
- `appendLine(text)` appends `text` as UTF-8 and a `\n` in that one write. A `\n` inside `text` is written as it is.
- `ownerOnly = true` leaves the file `600` before the first append. A new one is created so, and one already there is
  narrowed. One another user owns is `WriteFailed`. Windows ignores it.
- Through a `Writer`, a folder on the way that leads out is `Escapes`.
- Through a `Writer`, a link at the name is `NotRegularFile(path, Symlink)` and is never appended through.
  `replaceLink = true` puts a new file in its place.
- A folder, FIFO or device at the path is `NotRegularFile`. A write the system refuses is `WriteFailed`.
- `append` and `lastByte()` on a closed `AppendFile` are `Closed`.

### Reopen

- `Reopen.Never`, the default, keeps appending to the file it opened, wherever that file goes.
- `Reopen.WhenMoved()` asks `state()` before every append. On anything but `Same`, it opens the path again.
- The append then lands in the new file. That file is created as `openAppend` creates one.

## Tasks

### Append to a log another tool rotates

```kotlin
val state = dirs.state.writer().getOrElse { return report(it) }
val log = state.openAppend("app.log", Reopen.WhenMoved(), replaceLink = true).getOrElse { return report(it) }
log.use { for (line in lines) it.appendLine(line).onError { failure -> report(failure) } }
```

`replaceLink = true` suits a log only your app writes. Someone may put a link at its name, before the open or after a
rotation. That link is replaced by a new empty log, and what it led to is never appended to.

Keep the default when a link there may be meant, such as one a user points at a log kept elsewhere. The default
refuses such a link as `NotRegularFile`.

A reopen can fail. Then it is that append's error, and the append writes nothing. The next append tries again, so
keep the line and [append it again](#append-a-line-again-after-a-failed-append) to lose none.

### Start each new file with a header

```kotlin
val csv = state
    .openAppend(
        "samples.csv",
        Reopen.WhenMoved(onReopen = { file, _ -> file.append(header).onError(::report) }),
    )
    .getOrElse { return report(it) }
```

`onReopen` gets each file opened again. It runs after the reopen and before the append that caused it. It also gets
the `AppendState` of the file it replaces. What it throws is thrown from that append.

### See whether the last line was finished

```kotlin
val unfinished = log.lastByte().getOrElse { return report(it) }.let { it != null && it != '\n'.code.toByte() }
```

`lastByte()` reads only the last byte. It is null for an empty file.

### Append a line again after a failed append

```kotlin
fun AppendFile.appendLineAgain(line: String): EmptyResult<FileError> {
    val torn = lastByte().getOrElse { return Err(it) }.let { it != null && it != '\n'.code.toByte() }
    if (torn) append("\n".encodeToByteArray()).onError { return Err(it) }
    return appendLine(line)
}

log.appendLine(line).onError { log.appendLineAgain(line).onError(::report) }
```

A `WriteFailed` from the append may have written part of the line. Finishing that part with a `\n` first starts the
line again on a line of its own. After an append that wrote nothing, the last line is finished, so no `\n` is added.

## Reference

You do not need this part on a first read. Come back when a rotated log loses lines or a reopen does something you did
not expect.

**`state()`** says whether the path still names the open file:

- `Same` when it does.
- `Moved` when the file still has a name elsewhere, and `Deleted` when it has none.
- `Closed` once closed.
- `Unknown` where the platform cannot tell `Moved` from `Deleted`: the JVM on macOS and Windows. Close and open the
  path again, which is safe whichever it was.
- `state()` and `lastByte()` answer for the file open now, after any reopen.
- Sometimes the path cannot be looked up for another reason than nothing being there. A folder on the way you may not
  search is one. That is `Inaccessible`, naming the path.

**Denied:**

- A file this process may not open is `WriteFailed(cause = Denied)` on the JVM and Linux.
- A file whose last byte it may not read is `Inaccessible(cause = Denied)` from `lastByte()`.

**`ownerOnly`** applies on Linux, Apple, Android, and the JVM on a file system with POSIX permissions. The folders it
makes on the way are `700`, as [Create folders](folders-and-moves.md#create-folders) describes.

**`Reopen.WhenMoved`:**

- `checkEvery` is `Duration.ZERO` unless you pass another.
- `WhenMoved(checkEvery = 1.seconds)` asks at most once a second, timed by a monotonic clock. Appends in between go
  to the file it has open.
- If no file is there, a reopen creates one as `openAppend` does, `ownerOnly` included.
- A file moved between the check and the append still takes that append.
- So does one deleted between them, and that line is lost with it.
- `onReopen`'s `replaced` is `Moved` or `Deleted`. It is `Unknown` where the platform cannot tell, or where the state
  could not be looked up.
- Through a `Writer`, each reopen goes through the writer again, so a link put at the name is that append's
  `NotRegularFile`, and the next append tries again. With `replaceLink = true`, the new file replaces the link instead.
- A `WriteFailed` from the append itself may have written part of the line. `lastByte()` shows it.

**Rotators:**

- A rotator working with `Reopen.WhenMoved` renames the log, then creates a new one only if none is there yet, since
  the writer may already have reopened the path.
- Truncating or replacing that new file loses the lines it holds.
- `logrotate`'s `copytruncate` never moves the file, so `state()` stays `Same`, and appends go on at the new end of
  the emptied file.
- With `copytruncate`, lines appended between its copy and its truncate are lost.
