# Reading

Most reads are simple: load a file, or treat a missing one as empty. Some need more care. A config that runs
commands must not be one another user could change, and a huge file should not fill your memory.

These calls look a path up, and read a file whole or its start. Read a path the user picked as it is. Pass
`trusted = true` for a file that must be yours alone, such as a config that runs commands.

Part of [`fs`](../README.md).

```kotlin
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
inline fun <S> Result<S, FileError>.orIfNotFound(value: () -> S): Result<S, FileError>
fun <S> Result<S, FileError>.orNullIfNotFound(): Result<S?, FileError>
```

## Rules

These hold for every call on this page.

- Every call follows a symlink and answers for what it points at, except `isSymlink()` and
  `fileType(followLinks = false)`.
- `exists()` answers `false` both for a path that is not there and for one that cannot be looked up. A `Result`
  keeps them apart: `NotFound` or `Inaccessible`.
- `readText` and `readBytes` refuse a folder, FIFO, device or socket as `NotRegularFile` before opening it, since
  opening a FIFO blocks until something writes to it.
- `streams = true` reads a FIFO or a character device too, such as `/dev/stdin` or `/dev/null`, to its end.
  - The call may block. Opening a FIFO waits for a writer, and the read lasts until every writer has closed it. A
    terminal or a serial device is read until it ends, and on Linux its open may wait as well.
  - A stream has no size up front, so `maxBytes` is checked as it is read. Once more than that arrives, it is
    `TooLarge`. Give it for an endless device such as `/dev/zero`.
  - A folder, a socket and a `BlockDevice` stay `NotRegularFile`. A block device holds a whole disk, not a stream.
  - `trusted` judges the folder before the open, and the FIFO or device once it is open, so after its writer came.
  - Windows native reads a device such as `NUL` or `COM1` and a named pipe such as `\\.\pipe\name`, unverified on
    Windows. The C runtime's `stat` is asked about the path before kern types what is there, so a device or pipe may
    be `NotFound` first. Reading a named pipe likely fails, since each lookup opens it and so connects to its server
    as a client.
  - The JVM on Windows reads no stream, so there `streams` changes nothing.
- A file longer than `maxBytes` is `TooLarge`. `readPrefix(maxBytes)` reads at most that much and is never
  `TooLarge`.
- `readText` decodes UTF-8 and reads bytes that are not valid UTF-8 as U+FFFD. `readBytes` hands back the bytes.
- `orIfNotFound { value }` turns a `NotFound` into success with `value`, and leaves every other answer as it is.
  `value` is asked for only then.
- `orNullIfNotFound()` turns a `NotFound` into success with `null`.
- `trusted = true` refuses a file someone else owns, or one the group or others may write. It judges the folder the
  file sits in the same way. Either is `Refused(path, info, why, asked)`, and nothing is read.
- In that `Refused`, `path` is the refused file or folder. For a link it is the file the link leads to. `asked` is the
  path you read, so a refusal still tells you which read it stopped.
- Windows says neither who owns a file nor who may write it, so there `trusted` refuses nothing.
- `accept` is asked about the file once it is open, before any of it is read. `false` is
  `Refused(path, info, RefusedBecause.Accept, asked)`, with `path` naming the file as `trusted` does.

## Tasks

### Read a file that may be missing

```kotlin
val text = settings.readText(maxBytes = 1_048_576).orIfNotFound { "" }.getOrElse { return report(it) }
```

Read and handle `NotFound`. Do not check `exists()` first: that races a file that goes away in between.

### Read a config only you may change

```kotlin
val text = config.readText(maxBytes = 1_048_576, trusted = true).getOrElse { return report(it) }
```

A config that is a link into a dotfiles folder of your own loads. One in a folder opened to the group is `Refused`.

Read your app's config this way, as `(dirs.config / name).readText(maxBytes = 1_048_576, trusted = true)`. Do not
read it through a [Writer](writer.md#rules), since a writer refuses a link that leads out of its folder.

Write one back with `(dirs.config / name).writeText(text)`. It [follows a link](writing.md#rules) and replaces the file
in the dotfiles folder.

A `Writer` write is different. It refuses a link at the name as `NotRegularFile(path, Symlink)` and touches nothing.
With `replaceLink = true` it puts a regular file in the link's place, and the dotfiles folder keeps the old content.

A crash's backup may wait beside the file in the dotfiles folder. Until it is dealt with, the write back is
`PendingBackup` and writes nothing. Its `via` is the link at the name.
[Deal with what a killed write left](writer.md#deal-with-what-a-killed-write-left) restores or discards it with
`throughLinks = true`, since it lies where that link leads.

### Look at what is at a path

```kotlin
val info = path.info().getOrElse { return report(it) }
println("${info.type}, ${info.size} bytes, ${info.permissions}, written ${info.modifiedAt}")
```

`info()` reads all of these in one lookup: `type`, `size`, `modifiedAt`, `changedAt`, `permissions`, `ownedByMe`
and `links`, the number of hard links. Where the platform does not say, as on Windows, a field is null.

A cache keyed on `size` and `modifiedAt` can miss a change. A write of the same size may put an old time back, as
`modifiedAt` on a write and `keepAttributes` on a copy can. Key the cache on `changedAt` too. No call sets it, so it
moves on every write.

`changedAt` is null on Windows, and on the JVM without unix attributes. There size and time are all there is.

### Record what a read loaded

```kotlin
var loaded: FileInfo? = null
val text = config
    .readText(
        maxBytes = 1_048_576,
        accept = { info ->
            loaded = info
            true
        },
    )
    .getOrElse { return report(it) }
println("loaded ${text.length} characters of $config, written ${loaded?.modifiedAt}")
```

You often want to log which file a read loaded, and when it was written. A second look-up after the read could
find a different file, one put at the path after the read. `accept` avoids that.

`accept` is handed the info of the file the read opened, before any of it is read, so record it there. On the JVM,
Android and Windows the info is looked up by the path just after the open, as the [Reference](#reference) describes.

On a refused read whose `Refused.path` is the file, `Refused.info` is the `FileInfo` to record. `Refused.path` is
always the file for `Accept`. `accept` saw the same info, and a read `trusted` refuses never asks `accept`.

A trusted read can refuse a folder on the way instead. Then `path` and `info` name that folder.

### Reload a config when it changes

A long-running app should pick up a config edit without a restart. `textChanges()` from
[`fs-coroutines`](../../fs-coroutines/README.md#reload-a-file-when-its-text-changes) does that.

It reads the config at once. It reads it again each time its `info()` changes, or the mode or owner of its folder, a
`chmod` included. It answers only when the read differs from its last answer.

This example reads with `trusted = true`. It keeps what was loaded last while the config is missing or unsafe:

```kotlin
import com.fromwau.kern.fs.coroutines.textChanges

/** Loads [config] now and each time its text changes, for as long as the calling coroutine runs. */
suspend fun watchConfig(config: Path, load: (String) -> Unit) {
    config.textChanges(maxBytes = 1_048_576, trusted = true).collect { change ->
        change.onSuccess(load).onError { failure ->
            when (failure) {
                is FileError.NotFound -> println("$config is not there, so nothing new is loaded")
                is FileError.Refused -> println("${failure.path} is not yours alone")
                else -> println("$config not read: $failure")
            }
        }
    }
}
```

- A `chmod` or a write that leaves the text as it was loads nothing new.
- A missing config is said once, and loaded once it is back.
- An unsafe config names the first file or folder it finds unsafe. It is said again each time the refused file or
  folder changes, as the next read finds it. The config is loaded again once it is safe.
- The folder is checked on each read, so a folder opened to the group keeps the next change from loading.
- The config loads once a `chmod` closed the folder again, with no other change to it.

## Reference

The details of each call, grouped by what you ask. You need these when a result surprises you, or before you rely
on a platform.

**Looking up:**

- `fileType()` never answers `Symlink`. `fileType(followLinks = false)` answers `Symlink` for any link, dangling or
  looping, in one lookup.
- `fileSize()` is the byte count of a regular file. Anything else is `NotRegularFile`.
- `modifiedAt()` is a `kotlin.time.Instant`, to the precision the file system keeps.
- `isSymlink()` does not follow the path, so a dangling link is one too, and nothing at all is `NotFound`.
- Following a link is verified on Linux, Apple and the JVM. Windows native has not been verified.
- `realPath()` is the absolute path with every link resolved, for a path that exists.
- On Windows `realPath()` drops the `\\?\` prefix: `C:\x`, and a share as `\\server\share\x`.

**Permissions:**

- `permissions()` prints as `ls -l` does, `rw-r--r--`, and its `bits` are the number, `0b110_100_100` for `644`.
- `ownerRead`, `ownerWrite` and `ownerExecute` read the owner's bits.
- `othersMayRead` and `othersMayWrite` say whether the group or everyone else may.
- `anyExecute` says whether the owner, the group or everyone else may run it.
- No owner comes with it, so the bits alone cannot tell whether another user may write. `info().ownedByMe` says
  whether you own it.
- It is null on Windows, native or JVM, where who may read comes from the folder's access rules.

**`info()` by platform:**

- Windows, native or JVM, reads no `changedAt`, `permissions` or owner, so those are null. The JVM there reads no
  `links` either.
- The JVM on a file system with POSIX permissions but no unix attributes reads no `changedAt` and no setuid, setgid or
  sticky bits.
- Android reads the modification time in a second lookup, so a file replaced in between can mix the two.
- `changedAt` moves on a content or attribute change, such as a `chmod`, or a write that put an old modification
  time back.

**Trusted reads:**

- The file is judged as the read opened it, after following links. For a link, both folders are judged: the one the
  link sits in and the one the file really sits in, as `canonicalPath()` resolves it.
- `why` is `OtherOwner` or `OthersMayWrite`. The folders above the file's own folder are not checked.
- A [Writer](writer.md#reference)'s trusted read judges more folders, as its reference says.

**`accept`:**

- On Linux and Apple the `FileInfo` is read from the open file, so a file moved over the path after the open is never
  the one described.
- The JVM and Android look the path up after the open. They answer `Inaccessible` when its file key says it no
  longer names the file opened.
- On Windows the path is looked up after the open with no such check. The JVM there has no file keys, and native
  reads through kotlinx-io, which hands out no handle.
- A file refused before it is opened, as `TooLarge`, a folder, or by a trusted read, is never passed to `accept`.
- What `accept` throws is thrown from the read.
