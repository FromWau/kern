# logger-file

A log file sink for [`kern:logger`](../logger/README.md): `FileSink` appends whole lines to a file, in as few writes
as the queue allows, starts a new file when the old one is rotated or deleted, and reports every failure as a typed
`FileError` from [`kern:fs`](../fs/README.md).

```kotlin
import com.fromwau.kern.fs.div
import com.fromwau.kern.logger.*
import com.fromwau.kern.logger.file.FileSink
import com.fromwau.kern.result.*

val file = FileSink(dirs.state / "app.log", format = LogFormat.JSON)
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(file)) }
    .onError { error -> Log.tag("App").w(error) { "logging to the console only" } }
```

`path` is a kotlinx-io `kotlinx.io.files.Path`. Here `dirs` is an `AppDirs` from [`kern:dirs`](../dirs/README.md),
which reaches your classpath through `kern:fs`, and `/` is kern:fs's `com.fromwau.kern.fs.div`. `onError` comes
from [`kern:result`](../result/README.md#consuming-one), along with `onSuccess` and `isError()`.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. `kern:fs`, which `FileSink`
writes through, has no JS or Wasm target.

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

KMP consumers put it in `commonMain`. `kern:logger` and `kern:fs` come along as `api`, so `Log`, `Path`, `/`,
`AppDirs` and `FileError` are on your classpath. Needs what
[every kern module needs](../README.md#requirements). The class is `com.fromwau.kern.logger.file.FileSink`.

## The log file

`FileSink(path, format, name, onFailure, capacity, drop)` appends to the file at `path`. Only `path` is required:
`format` is `LogFormat.TEXT`, `name` is `file:<path>`, `onFailure` is `SinkFailure.Retry()`, `capacity` is 10,000
and `drop` is `DropPolicy.OLDEST`. You hand over a finished path, and kern opens exactly that.
It never expands a `~` and never picks a directory of its own. Missing parent directories are created.

A lone entry is appended at once, in one write. When several are waiting in the queue, as under a burst, they go
out together, up to 256 lines in one write, which lets the file keep up with a far heavier load. Each write holds
only whole lines. A crash keeps everything already written. Two processes or two loggers writing to one local file
never tear each other's lines; a network file system does not promise that. After a failed write, such as a full
disk, the next entry starts on a line of its own.

A write that fails fails for all its lines: `Retry` tries them again together, and `Drop` loses them together,
each counted in `health.dropped`.

`format` is `LogFormat.TEXT` for a person or `LogFormat.JSON` for a log shipper, one object per line; the logger's
README describes [both](../logger/README.md#line-formats). A log file is never coloured.

## Failures

A failure is `SinkError.Dropped`, carrying kern:fs's `FileError`, and by default the entry is retried every second
until it is written. For a path the user typed on the command line, pass
`onFailure = SinkFailure.Drop()` instead. Under `Retry`, a path that cannot be opened keeps its entries queued, and
`close()` retries them for its whole timeout. Under `Drop` they are lost at once, and `close()` returns without
waiting. The logger's README shows [the recipe](../logger/README.md#console-sinks).

| `FileError`            | when                                                                       |
|------------------------|----------------------------------------------------------------------------|
| `WriteFailed(reason)`  | the file would not open or the write was refused, such as on a full disk   |
| `NotRegularFile(type)` | a directory, FIFO or device stands at the path                             |
| `NotADirectory(type)`  | a file stands where a parent directory should be                           |
| `Inaccessible(reason)` | the path may not be looked up                                              |

`reason` says why, such as `No space left on device`, and differs per platform. On native it is the system's own
message. On the JVM it is the message of the exception Java threw, or that exception's class name when it has no
message, so do not match on its text.

```kotlin
when (val error = installed.health.value.lastError) {
    is SinkError.Dropped -> (error.error as? FileError)?.let { showBanner("The log file failed: $it") }
    else -> Unit
}
```

## Rotation and deletion

**Rotation needs no hook.** When logrotate moves the file away or someone deletes it, the sink notices on its next
write and starts a new file at the path. It looks at the path at most once every ~100 ms, so a write within 100 ms
of the last look appends to the file it has open. Lines written to a moved file before the move was noticed stay
in it. A `copytruncate` rotation works too, since every write appends at the file's current end. A rotation that
leaves another hard link to the file is a move: its lines stay in the linked file.

Lines written in the ~100 ms before a deletion was noticed are written again at the start of the new file, up to
the sink's `capacity` of them. Lines written before that window went with the deleted file: they are not written
again and not counted as dropped, so a file deleted after a quiet spell starts with the next entry, and `flush` and
`close` report no loss for it. A close of the sink in that window writes them at once. Native builds and the JVM on
Linux and Android do this. The JVM on macOS cannot tell a deleted file from a moved one, so there the lines of that
window are counted as dropped. Any line of that window not written again shows in `health.dropped` and in a warning
line in the new file.

Writing them again can fail, as any write can. That is a failure of the sink like any other: `health` shows it in
`failingSince` and `lastError`, and the first failure goes to standard error. The next attempt tries the lines
again. A close that cannot write them counts them as dropped, so `Log.close()` returns a `CloseError` naming the sink
with that `FileError` in `SinkError.Dropped`, then `SinkError.Lost` with how many lines it dropped.

Calling `close()` on its `InstalledSink` releases the open file at any time. The next entry opens it again.

## Platform notes

**Windows.** A native Windows build notices a moved or deleted file the same way. The JVM on Windows opens the file
so that no other process can move or delete it while it is open. There a rotation succeeds only between a `close()`
on its `InstalledSink` and the next entry, which opens the file again.

**A file size limit ends a native process.** Under `ulimit -f` or systemd's `LimitFSIZE=`, the write that crosses
the limit ends the process with `SIGXFSZ`. Ignore `SIGXFSZ` at the start of `main`, and the write fails like a full
disk instead: `WriteFailed` with the reason `File too large`.

## License

[Apache-2.0](../LICENSE).
