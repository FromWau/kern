# logger-file

A log file is the simplest place to keep what your app logged. It stays when the app crashes, and logrotate can
manage it. `FileSink` is that sink for [`kern:logger`](../logger/README.md). It appends whole lines to a file, in as
few writes as the queue allows. It starts a new file when the old one is rotated or deleted. Every failure comes
back as a typed `FileError` from [`kern:fs`](../fs/docs/types.md).

```kotlin
import com.fromwau.kern.fs.writer
import com.fromwau.kern.logger.*
import com.fromwau.kern.logger.file.FileSink
import com.fromwau.kern.result.*

val state = dirs.state.writer().getOrElse { return warn(it) }
val file = FileSink(state, "app.log", format = LogFormat.JSON)
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(file)) }
    .onError { error -> Log.tag("App").w(error) { "logging to the console only" } }
```

- `dirs` is an `AppDirs` from [`kern:dirs`](../dirs/README.md). It reaches your classpath through `kern:fs`.
- `writer()` is kern:fs's [`Writer`](../fs/docs/writer.md).
- `getOrElse` and `onError` come from [`kern:result`](../result/README.md#consuming-one), along with `onSuccess`
  and `isError()`.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. There is no JS or Wasm
target, because `kern:fs`, which `FileSink` writes through, has none.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:logger-file:$kernVersion")
}
```

- KMP consumers put it in `commonMain`.
- `kern:logger` and `kern:fs` come along as `api`, so `Log`, `Path`, `/`, `AppDirs` and `FileError` are on your
  classpath.
- The class is `com.fromwau.kern.logger.file.FileSink`.
- Needs what [every kern module needs](../README.md#requirements).

## The log file

There are two ways to make a `FileSink`. Pick by who owns the folder.

**Your app's own log.** `FileSink(writer, name, format, sinkName, onFailure, capacity, drop)` appends to the file
`name` below the writer's root. Use it for your app's log in `dirs.state`.

- The folders on the way keep to that root.
- A symlink planted at `name` is replaced by a new file. It is never written through.
- Missing folders are created.

**A file the user picked.** `FileSink(path, format, name, onFailure, capacity, drop)` appends to the file at `path`.

- You hand over a finished kotlinx-io `Path`, and kern opens exactly that.
- It never expands a `~` and never picks a folder of its own.
- Missing parent folders are created.

Only the file is required. The rest has defaults:

| parameter   | default                 |
|-------------|-------------------------|
| `format`    | `LogFormat.TEXT`        |
| sink's name | `file:<path>`           |
| `onFailure` | `SinkFailure.Retry()`   |
| `capacity`  | 10,000                  |
| `drop`      | `DropPolicy.OLDEST`     |

`format` is `LogFormat.TEXT` for a person. It is `LogFormat.JSON` for a log shipper, with one object per line. The
logger's README describes [both](../logger/README.md#line-formats). A log file is never coloured.

### How lines are written

- A lone entry is appended at once, in one write.
- When several entries wait in the queue, as under a burst, they go out together. One write holds up to 256 lines.
  That lets the file keep up with a far heavier load.
- Each write holds only whole lines.
- A crash keeps everything already written.
- Two processes or two loggers writing to one local file never tear each other's lines. A network file system does
  not promise that.
- After a failed write, such as a full disk, the next entry starts on a line of its own.
- A write that fails fails for all its lines. `Retry` tries them again together. `Drop` loses them together, and
  each one is counted in `health.dropped`.

## Failures

A file can fail to open or fill up, and you want to know rather than lose lines quietly. A failure is
`SinkError.Dropped`, carrying kern:fs's `FileError`. By default the entry is retried every second until it is
written.

For a path the user typed on the command line, pass `onFailure = SinkFailure.Drop()` instead. The logger's README
shows [the recipe](../logger/README.md#console-sinks).

- Under `Retry`, a path that cannot be opened keeps its entries queued. `close()` retries them for its whole timeout.
- Under `Drop`, they are lost at once, and `close()` returns without waiting.

| `FileError`                      | when                                                                     |
|----------------------------------|--------------------------------------------------------------------------|
| `WriteFailed(cause, exception)`  | the file would not open or the write was refused, such as on a full disk |
| `NotRegularFile(type)`           | a folder, FIFO or device stands at the path                              |
| `NotADirectory(type)`            | a file stands where a parent folder should be                            |
| `Inaccessible(cause, exception)` | the path may not be looked up                                            |
| `InvalidRelativePath`, `Escapes` | a `NameRefused`: a `name` a sink's `Writer` refuses, or a link out of it |
| `FolderReplaced`                 | the folder of a sink's `Writer` was moved, deleted or replaced           |

`cause` is an `IoCause` such as `Denied` or `NoSpace`. It is typed only where the platform says so without its
message being read. Otherwise it is `Other`, with the platform's own exception in `exception`.

- A native build types a full disk as `NoSpace` and a refused open as `Denied`.
- On the JVM a refused open is `Denied` too. A full disk is `Other`, since Java reports it with an exception whose
  type does not say why.
- The [fs Types page](../fs/docs/types.md#iocause) has the details.

```kotlin
when (val error = installed.health.value.lastError) {
    is SinkError.Dropped -> (error.error as? FileError)?.let { showBanner("The log file failed: $it") }
    else -> Unit
}
```

## Rotation and deletion

**Rotation needs no hook.** When logrotate moves the file away, or someone deletes it, the sink notices on its next
write. It then starts a new file at the path.

- It looks at the path at most once every ~100 ms, so a write within 100 ms of the last look appends to the file it
  has open.
- Lines written to a moved file before the move was noticed stay in it.
- A `copytruncate` rotation works too, since every write appends at the file's current end.
- A rotation that leaves another hard link to the file is a move. Its lines stay in the linked file.

Calling `close()` on its `InstalledSink` releases the open file at any time. The next entry opens it again.

### Details: lines around a deletion

Lines written in the ~100 ms before a deletion was noticed are written again at the start of the new file. That
goes for up to the sink's `capacity` of them.

Lines written before that window went with the deleted file. They are not written again and not counted as dropped,
so a file deleted after a quiet spell starts with the next entry, and `flush` and `close` report no loss for it.

A close of the sink in that window writes them at once. Native builds and the JVM on Linux and Android do this.
The JVM on macOS cannot tell a deleted file from a moved one, so there the lines of that window are counted as
dropped. Any line of that window not written again shows in `health.dropped` and in a warning line in the new file.

Writing them again can fail, as any write can. That is a failure of the sink like any other:

- `health` shows it in `failingSince` and `lastError`.
- The first failure goes to standard error.
- The next attempt tries the lines again.
- A close that cannot write them counts them as dropped, so `Log.close()` returns a `CloseError` naming the sink,
  with that `FileError` in `SinkError.Dropped`, then `SinkError.Lost` with how many lines it dropped.

## Platform notes

**Windows.** A native Windows build notices a moved or deleted file the same way. The JVM on Windows opens the file
so that no other process can move or delete it while it is open. There a rotation succeeds only between a `close()`
on its `InstalledSink` and the next entry, which opens the file again.

**A file size limit ends a native process.** Under `ulimit -f` or systemd's `LimitFSIZE=`, the write that crosses
the limit ends the process with `SIGXFSZ`. Ignore `SIGXFSZ` at the start of `main` to avoid that. The write then
fails like a full disk instead: `WriteFailed` for `Other`, whose exception says `File too large`.

## License

[Apache-2.0](../LICENSE).
