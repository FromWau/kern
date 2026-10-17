# logger

A Kotlin Multiplatform logger that never throws and never makes your code wait for a sink.

Every destination is a sink with its own queue and worker. A log call queues the entry and returns, so a slow
disk or a failing network holds up only that one sink. Every failure is a typed error or a count you can read.

```kotlin
Log.tag("Scanner").i { "scan complete" }   // printed to the platform's log, no setup needed
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64, js, wasmJs.

## Add to your build

Releases are published as `com.fromwau.kern:logger` to
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/logger), which needs no
credentials to read.

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:logger:$kernVersion")
}
```

KMP consumers put it in `commonMain`. Needs what [every kern module needs](../README.md#requirements).
Everything is in one package, `com.fromwau.kern.logger`.

Your compile classpath gets [`kern:result`](../result/README.md) and `kotlinx-coroutines-core`, because `Result`
and `StateFlow` are part of the API. On native, JS and Wasm, `kern:result` brings `kotlinx-serialization-core` onto
it too. [`kern:terminal`](../terminal/README.md), `atomicfu`, `kotlinx-datetime` and `kotlinx-serialization-json`
come along at runtime only; add `kern:terminal` yourself to match the `WriteError` a console sink fails with.

The log file sink, `FileSink`, is a module of its own: [`logger-file`](../logger-file/README.md). So is
`SentrySink`, which sends entries to Sentry: [`logger-sentry`](../logger-sentry/README.md).

## Logging

```kotlin
private val log = Log.tag("Scanner")

log.v { "visiting $dir" }
log.d { "cache hit for $key" }
log.i { "scan complete" }
log.w { "disk is slow" }
log.e { "scan failed" }
log.wtf { "index went negative" }
```

The levels, least severe first, are `VERBOSE`, `DEBUG`, `INFO`, `WARN`, `ERROR` and `WTF`. `OFF` is a sink level
that takes nothing. An entry logged at `OFF` is discarded.

The message is a lambda. It is built only when a configured sink takes the level, so a filtered
`log.d { expensive() }` costs a level check. A message block that throws anything, an `Error` included, is
logged with `<message threw IllegalStateException>` in its place.

A log call never suspends and never throws, from any thread. It never waits for a sink to write. It may wait
briefly for a lock while it queues the entry, and while a hold is on.

### Fields

Add a field for a value a log tool should filter on, such as a count, a duration or a request id:

```kotlin
log.i("count" to files.size, "ms" to elapsed.inWholeMilliseconds, "cached" to true) { "scan complete" }
```

```
2026-08-11T14:34:56.789+02:00 INFO    Scanner - scan complete count=412 ms=1200 cached=true
{"timestamp":"2026-08-11T12:34:56.789000000Z","tag":"Scanner","level":"INFO","message":"scan complete","fields":{"count":412,"ms":1200,"cached":true}}
```

| value                                             | JSON                     | text         |
|---------------------------------------------------|--------------------------|--------------|
| `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` | a number: `5001`         | `port=5001`  |
| `Boolean`                                         | `true` or `false`        | `tls=true`   |
| `null`                                            | `null`                   | `user=null`  |
| `NaN` or an infinity                              | a string: `"NaN"`        | `ratio=NaN`  |
| an array, a collection or a map                   | its contents: `"[1, 2]"` | `ids=[1, 2]` |
| anything else                                     | its `toString()`         | the same     |

A value is turned into text when the sink writes the entry, on that sink's worker. Pass values that will not
change, such as a copy of a list rather than the list itself. A `toString()` or an iteration that throws is written as
`<toString() threw IllegalStateException>`. A key passed twice keeps its last value.

### Exceptions and errors

```kotlin
log.e(cause) { "scan failed" }                    // the stack trace goes under the message
log.w(timeout, "attempt" to 3) { "retrying" }
log.w(DbError.NotFound(id)) { "no such user" }    // a ResultError, written as error=NotFound(id=7)
```

`logError` logs a `Result`'s error, when it is one, and hands the result back unchanged:

```kotlin
val user = repository.find(id).logError(log) { "lookup of $id failed" }
```

It logs at `ERROR` unless you pass `level`. On a success it logs nothing and never builds the message.

### Forwarding entries from elsewhere

`Logger.log` takes the level as a value, and `at` keeps the time the entry happened rather than now:

```kotlin
Log.log(remote.tag, remote.level, at = remote.time) { remote.message }
```

## Sinks

Every destination is a `LogSink`, the built-in ones included. `Log` and every new `Logger()` start with one,
`PlatformSink()`, at `INFO`:

| platform                         | `PlatformSink` writes to                                                   |
|----------------------------------|----------------------------------------------------------------------------|
| JVM, Linux, Windows              | standard output, as text coloured for its level                            |
| macOS, a command-line tool       | standard output, as on Linux                                               |
| iOS, a macOS app                 | the unified log (`os_log`)                                                 |
| Android                          | logcat, at the matching priority                                           |
| JS, Wasm                         | `console.debug`, `info`, `warn` or `error`, by level                       |

The sinks live in the logger's config, each in a `ConfiguredSink` with its `SinkConfig`: the least severe `level`
it takes, `INFO` by default, and its `timestamp`. Add one with `configure`, here a `FileSink` from
[`logger-file`](../logger-file/README.md):

```kotlin
import com.fromwau.kern.logger.*
import com.fromwau.kern.logger.file.FileSink
import com.fromwau.kern.result.*
import kotlinx.io.files.Path

val file = FileSink(Path(stateDir, "app.log"), format = LogFormat.JSON)
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(file, SinkConfig(level = LogLevel.DEBUG))) }
    .onError { error -> log.w(error) { "logging to the console only" } }
```

`configure`, `flush` and `close` return a [`kern:result`](../result/README.md#consuming-one) `Result`.
`import com.fromwau.kern.result.*` brings in `Ok`, `Err` and the `onSuccess`, `onError` and `isError()` the
snippets here use. Without it the compiler says `Unresolved reference 'onError'`.

`configure` swaps the whole list in one step. Every entry logged before the change goes to the old list's sinks by
their old levels, and every entry after it to the new list's sinks by their new ones. It returns a
`Result<Unit, ConfigError>`, and on an error nothing changes:

- `ConfigError.NameTaken`: two sinks in the new list share a name, or one sink is listed twice.
- `ConfigError.Invalid`: a new sink's capacity is below one, its backoff is negative, or one of its properties
  throws.

The lambda runs under the logger's lock, so keep it to a `copy`. Two calls from different threads never undo each
other's change. If the lambda throws, nothing changes and the exception reaches you.

A sink in both lists, the same object, keeps its queue and health, and takes the `config` of its `ConfiguredSink`
in the new list. A removed sink takes no further entries. It writes what it had queued and is then closed in the
background, so `configure` returns at once. A removed sink that keeps failing under `Retry` gives up after five
seconds and counts what it had left as dropped. One whose single write runs for five seconds without returning is
abandoned, as [a write that hangs](#a-write-that-hangs) explains. A sink added back, while it still drains or after
it was closed or abandoned, takes the `config` it is given then.

`Log.config` holds the `LoggerConfig` in force. `Log.sinks` holds an `InstalledSink` for each of its sinks, in the
same order, which is also the order each entry reaches them. An `InstalledSink` is your handle to one sink's
`name`, `config`, `health`, `queued` count, `state` and `close()`. Find the one for a sink you added by comparing
`sink`:

```kotlin
val installed: InstalledSink? = Log.sinks.value.firstOrNull { it.sink === file }
```

A removed sink leaves `Log.sinks` at once, while it still writes what it had queued. Keep its `InstalledSink` from
before the change to watch it drain: `queued` counts down as the sink writes, `health` goes on counting its
failures and drops, and `config` keeps the settings it had when it was removed. Its `state` is a
`StateFlow<SinkState>`:

- `Active` while the sink is configured, also after you call its `close()`.
- `Draining` from the `configure` that removed it until it has written its queue, given up on it under `Retry`,
  or been abandoned. Added back now, it keeps its handle and is `Active` again.
- `Closed` once it is closed for good. Its `close()` then returns `ClosedAlready`, and adding the sink back gives
  it a new handle.
- `Abandoned` when, after the removal, one write ran for five seconds without returning. The entries in that
  write and those still queued count as lost, and no flush or close waits for the sink again. Its `close()` and
  adding it back behave as after `Closed`.

To wait until a removed sink is closed, before you delete the file it wrote, say:

```kotlin
val old = Log.sinks.value.single { it.sink === file }
Log.configure { config -> config.copy(sinks = config.sinks.filter { it.sink !== file }) }
    .onError { error -> System.err.println("still logging to the file: $error") }
withTimeoutOrNull(10.seconds) { old.state.first { it == SinkState.Closed } }
```

An `Abandoned` sink may still be inside the write it hung in, so this waits for `Closed` alone.

`Log.sinks` lists what the config says, in config order. `Log.draining` lists the removed sinks still `Draining`,
oldest removal first. A sink leaves it once it is closed or abandoned, or added back. An `Abandoned` sink moves
from `Log.draining` to `Log.abandoned`, oldest abandonment first, and leaves that once its stuck call has returned
and its `close()` has run. Only a removed sink whose one write has run for five seconds reaches `Log.abandoned`. It
joins `Log.abandoned` before it leaves `Log.draining`, so a panel never loses it, though for a moment it may show
in both. To show every sink in a panel:

```kotlin
scope.launch {
    combine(Log.sinks, Log.draining, Log.abandoned) { sinks, draining, abandoned ->
        sinks.map { "${it.name}: active" } +
            draining.map { "${it.name}: ${it.queued} left to write" } +
            abandoned.map { "${it.name}: stuck in a write, its entries lost" }
    }.collect { lines -> panel.show(lines) }
}
```

An `InstalledSink` prints as `InstalledSink(name=file:app.log, state=Draining, queued=4)`.

### Built-in sinks

Each built-in sink is a class, so you can read back what it was built with. Every parameter has a default but
`FileSink`'s `path`:

| sink          | parameters, with their defaults                                                                       |
|---------------|-------------------------------------------------------------------------------------------------------|
| `PlatformSink`| `name = "platform"`, `onFailure = SinkFailure.Drop()`, `capacity = 10_000`, `drop = DropPolicy.OLDEST` |
| `StdoutSink`  | `format = LogFormat.TEXT`, `color = ColorMode.AUTO`, `name = "stdout"`, then `onFailure`, `capacity`, `drop` as above |
| `StderrSink`  | the same as `StdoutSink`, with `name = "stderr"`                                                      |
| `FileSink`    | `path`, `format = LogFormat.TEXT`, `name = "file:<path>"`, `onFailure = SinkFailure.Retry()`, then `capacity`, `drop` as above |

Pass `name` to add two of one kind. A name is free again in the same change that removes its sink.

`FileSink` writes the lines waiting in its queue together, up to 256 in one append, and a lone entry at once. The
console sinks, and every sink of your own, get one `write` per entry.

### Console sinks

`StdoutSink()` and `StderrSink()` write text or JSON to that stream. They exist on the JVM, Linux, Windows and
macOS. A CLI usually wants its logs on standard error, so `app | jq` reads only its data. Swap the platform sink
for one in a single change:

```kotlin
Log.configure { config ->
    config.copy(sinks = config.sinks.filterNot { it.sink is PlatformSink } + ConfiguredSink(StderrSink()))
}.onError { error -> System.err.println("no log output: $error") }
```

For a log file at a path the user typed on the command line, choose `SinkFailure.Drop()`. Under
`FileSink`'s default `Retry`, a path that cannot be opened, such as one in a directory the user may not write to,
keeps its entries queued, and `close()` retries them for its whole timeout before it gives up. Under `Drop` they
are lost at once and `close()` returns without waiting, reporting the failure:

```kotlin
val logFile = FileSink(Path(arg), onFailure = SinkFailure.Drop())
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(logFile)) }
    .onError { error -> System.err.println("no log file: $error") }
```

### A sink of your own

```kotlin
val crashes = LogSink("crashes") { entry, _ ->
    crashReporter.record(entry.message, entry.throwable)
    Ok(Unit)
}
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(crashes, SinkConfig(level = LogLevel.ERROR))) }
    .onError { error -> log.w(error) { "no crash reports" } }
```

`LogSink(name, onFailure, capacity, drop) { entry, config -> }` takes:

| parameter   | default              | what it sets                                                                    |
|-------------|----------------------|---------------------------------------------------------------------------------|
| `name`      | none, required       | the sink's name in `Log.sinks`, errors and health; unique within a config       |
| `onFailure` | `SinkFailure.Drop()` | what a failed write leads to, see [Failures](#failures)                         |
| `capacity`  | `10_000`             | how many entries may wait for the sink                                          |
| `drop`      | `DropPolicy.OLDEST`  | which waiting entry gives way when the queue is full                            |
| the lambda  | none, required       | writes one entry, given its settings; returns `Ok(Unit)` or `Err(SinkError)`    |

Your sink is handed a `LogEntry`: `timestamp`, `tag`, `level`, `message`, `fields`, `throwable` and `error`, as
data, and the `SinkConfig` its `ConfiguredSink` gives it. It runs on the sink's own worker, one entry at a time and
in the order they were logged, so it may block. Return an `Err`, or throw anything, an `Error` included, and the sink's
`onFailure` decides what happens next. A throw is the failure `SinkError.Dropped(Thrown(e))`; it never ends the
sink's worker. An entry your sink logs while writing goes to every other sink, never back to itself.

#### A write that hangs

The logger never interrupts a write. While the sink is configured, it never times one out either: a call that
hangs holds up that sink's queue, and every flush or close waits for it until its own timeout.

A sink you removed with `configure` gets five seconds per write. Once one write has run for five seconds without
returning, whether it started before or after the removal, the sink is `Abandoned`: the entries in that write and
those still queued count as lost under its name, and no flush or close waits for it from then on. A flush or close
already waiting stops waiting for it at that moment. If the write returns later, its result is ignored and the
sink's `close()` runs; entries it wrote then still count in `Lost`, since they were counted when it was
abandoned. A removed sink whose writes keep returning, however slowly, writes its whole queue.

The sink then leaves `Log.draining` and is listed in `Log.abandoned` until its stuck call returns. Added back
while that call still runs, the same object gets a new handle at once, which makes no call into it until the
abandoned write and its `close()` have returned; until then its queue fills and drops as its `drop` says. So when
the old sink may be stuck, build a new sink instance on reload rather than adding the same object back.

On the JVM and native, a write that hangs holds its own thread until it returns, never one that other sinks or
your app need, so other sinks go on writing and your own `Dispatchers.IO` work goes on running.

So give a network call its client's own timeout, and return `Err(SinkError.Dropped(reason))` when it fails, with
your own `ResultError` as the reason or `Thrown(e)` for the exception:

```kotlin
val remote = LogSink("remote", onFailure = SinkFailure.Drop(backoff = 30.seconds)) { entry, _ ->
    try {
        httpClient.send(entry)   // with the client's own timeout set
        Ok(Unit)
    } catch (e: IOException) {
        Err(SinkError.Dropped(Thrown(e)))
    }
}
```

Implement the `LogSink` interface instead when your sink holds something open. Its `close()` is called when the
logger closes the sink, never during a `write`, and the next entry after a close must reopen what it needs. A
`close()` that returns an error, or throws, is a failure of the sink like a failed write. The interface's members:

| member                 | default             | what it does                                                         |
|------------------------|---------------------|----------------------------------------------------------------------|
| `name`                 | none, required      | as for the lambda form above                                         |
| `onFailure`            | `SinkFailure.Drop()`| as above                                                             |
| `capacity`             | `10_000`            | as above                                                             |
| `drop`                 | `DropPolicy.OLDEST` | as above                                                             |
| `write(entry, config)` | none, required      | writes one entry; returns `Ok(Unit)` or `Err(SinkError)`             |
| `close()`              | returns `Ok(Unit)`  | releases what the sink holds open; returns `Ok(Unit)` or an `Err`    |

```kotlin
class UploadSink(private val client: UploadClient) : LogSink {
    override val name = "upload"
    override val onFailure = SinkFailure.Retry(backoff = 5.seconds)

    override fun write(entry: LogEntry, config: SinkConfig): EmptyResult<SinkError> =
        if (client.send(entry.message)) Ok(Unit) else Err(SinkError.Dropped(UploadError.Refused))

    override fun close(): EmptyResult<SinkError> {
        client.disconnect()
        return Ok(Unit)
    }
}
```

### Changing levels

Change a sink's settings with `configure`, giving its `ConfiguredSink` a new `SinkConfig`. A settings screen that
sets one level for every sink maps each one:

```kotlin
fun setLogLevel(level: LogLevel): Result<Unit, ConfigError> = Log.configure { config ->
    config.copy(sinks = config.sinks.map { it.copy(config = it.config.copy(level = level)) })
}
```

To change one sink, change only its `ConfiguredSink`:

```kotlin
fun setLogLevel(sink: LogSink, level: LogLevel): Result<Unit, ConfigError> = Log.configure { config ->
    config.copy(
        sinks = config.sinks.map { each ->
            if (each.sink === sink) each.copy(config = each.config.copy(level = level)) else each
        },
    )
}
```

A new `level` applies from the next entry logged, to every sink of the change at once, so no entry
is taken by one sink's old level and another's new one. A new `timestamp` also applies to entries already queued.
`Timestamp.Off` starts each text line at the level, for a collector such as journald that stamps lines itself.
Under load an entry waits in the sink's queue before it reaches the console, so the collector's stamp is when the
line arrived, not when it was logged. Keep `Timestamp.On` where exact times matter.
Read an `InstalledSink`'s `config` to show the current setting, or collect it to react to a change.

## Failures

A sink that fails costs that sink the entry and nothing else. The other sinks still get it, and the code that
logged never sees the failure. Each sink chooses what a failed write leads to:

| `onFailure`                    | the failed entry                                   | default for          |
|--------------------------------|----------------------------------------------------|----------------------|
| `SinkFailure.Drop(backoff)`    | lost and counted; so is every entry for `backoff`  | every sink but files |
| `SinkFailure.Retry(backoff)`   | tried again after `backoff` until it is written    | `FileSink`           |

`backoff` is one second by default. Under `Drop` the sink is not called while the backoff runs, so a sink that
times out on every call cannot slow anything down. Under `Retry` later entries wait in line behind the failed one,
and a flush or close makes the sink try again at once.

Each sink queues up to `capacity` entries, 10,000 by default. When the queue is full, `drop` decides which entry
gives way: `DropPolicy.OLDEST`, the default, keeps the most recent context, and `DropPolicy.NEWEST` keeps the
lead-up to the failure.

Once the sink takes a write again after dropping entries, a warning in their place says how many and when:

```
2026-08-11T14:05:40.112+02:00 WARN    kern.logger - dropped 312 entries this sink did not take, logged between 2026-08-11T12:02:11.034Z and 2026-08-11T12:05:40.101Z dropped=312
```

The warning is logged at `WARN` with the tag `kern.logger`, and carries the count in its field `dropped`, a `Long`.
It reaches the sink that dropped the entries whatever that sink's level, so a crash reporter at `ERROR` sees it
too. A sink gets at most one such warning a second. Drops in between add to the next warning's count, and a close
writes one still pending.

### Watching a sink's health

```kotlin
Log.sinks.value.forEach { sink ->
    scope.launch {
        sink.health.collect { health ->
            val since = health.failingSince
            if (since != null) showBanner("Logging has failed since $since: ${health.lastError}")
        }
    }
}
```

| field          | what it holds                                                                 |
|----------------|-------------------------------------------------------------------------------|
| `dropped`      | entries the sink never got: a full queue, a `Drop` failure or its backoff,    |
|                | what a close gave up on, or lines of a deleted log file a `FileSink` could    |
|                | not write again                                                               |
| `failures`     | writes that failed, retries included, and closes that failed                  |
| `failingSince` | when the current run of failures began; null once a write succeeds again      |
| `lastError`    | the most recent `SinkError`, kept after the sink recovers                     |

`health` belongs to one `InstalledSink` and starts at zero for a new one, so a reload that builds its sinks anew
starts a fresh count. A flush and a close sum every sink of a name, removed ones included, as
[shutdown and tests](#shutdown-and-tests) explains.

`health` changes only when something is dropped, fails or recovers, so collecting it costs nothing while all is
well. Entries a full queue drops show in it once the sink takes its next entry or a flush or close starts, and
reading `health` counts them at once.

`queued`, on the `InstalledSink`, is an `Int`: how many entries are waiting at the moment you read it, those being
written included. It is not a flow, so read it again to see it change. Any sink can hold its `capacity` plus the
entry being written, and a `FileSink` its `capacity` plus up to 256, which it writes at once.

A `SinkError` is one of four cases:

- `Dropped(error)` is a sink that could not take the entry, with its own typed reason: a console stream's
  `WriteError` from [`kern:terminal`](../terminal/README.md#failed-writes) saying which stream and why, a
  `FileSink`'s `FileError` from [`kern:fs`](../fs/README.md), or `Thrown(throwable)` for anything the sink's write or
  close threw, passed through as it is.
- `TimedOut(pending)` is a close whose time ran out with `pending` entries not yet written.
- `Lost(count)` is a close finding `count` entries lost under a sink's name since it was first added, a sink
  removed and added back included.
- `ClosedAlready` comes in the `CloseError` that `close()` on an `InstalledSink` returns once its sink was removed
  and has been closed for good. `Log.close()` never reports it. A sink of your own that cannot take an entry once
  closed may return it from `write`, where it shows in `health.lastError`. No built-in sink does, since each
  reopens what it needs.

```kotlin
when (val error = health.lastError) {
    is SinkError.Dropped -> showBanner("Logging failed: ${error.error}")
    is SinkError.TimedOut, is SinkError.Lost, SinkError.ClosedAlready, null -> Unit
}
```

The first failure under each sink name is also reported in one line on standard error, or `console.error` on JS and
Wasm, whatever the levels say. In an Android app standard error goes nowhere, and in an iOS app it shows only in
Xcode's console while you debug, so watch `health` there:

```
kern.logger: sink "file:/var/log/app.log" failed: Dropped(error=WriteFailed(path=/var/log/app.log, reason=Permission denied))
```

Later failures under that name only count in `health`, until a sink of that name writes successfully; its next
failure is reported again. A reload that builds a failing sink anew under the same name does not repeat the line.
Like console lines, it goes to the process's own standard error, so `System.setErr` does not capture it.

## Holding startup entries

An app that reads its log level from its own config logs before it knows that level. Hold those entries and let
the config decide what they were worth:

```kotlin
Log.holdUntilConfigured()
Log.tag("Startup").d { "looking for a config file" }       // held
val config = readConfig()
setLogLevel(config.logLevel)                               // from "Changing levels"
    .onError { error -> Log.tag("Startup").w(error) { "keeping the default level" } }
Log.flush()                                                // replayed if DEBUG is on
    .onError { error -> System.err.println("startup logs were lost: $error") }
```

`flush()`, `awaitFlushed()`, `close()` and `awaitClosed()` end the hold. The held entries then reach each sink
configured at that moment whose level takes them, with the time they were logged. A sink you add during the hold
gets them too, and one you remove gets none. A held entry builds its message at once, since no level has
decided against it yet.

At most 1024 entries are held. Past that the oldest give way, `heldDropped` counts them, and the replay starts
with a warning saying how many.

**End the hold yourself, on every way out of your startup**, error paths included. Nothing ends it for you, and
entries still held when the process ends are lost. In a CLI that logs to standard output, swap in `StderrSink`
before you end the hold, so a bad flag does not replay startup lines into the data a pipe reads.

## Shutdown and tests

Close the logger where your app exits:

```kotlin
fun main() {
    try {
        run()
    } finally {
        Log.close().onError { failed ->
            failed.errors.forEach { (name, errors) -> System.err.println("$name: $errors") }
        }
    }
}
```

`close(timeout)` waits up to `timeout`, five seconds by default, for every sink's queue, then closes each sink. It
waits for a sink you removed with `configure` too, while that sink still writes what it had queued. A sink in a
`Retry` backoff tries again at once. A `Retry` write that fails while close waits is tried again after the sink's
backoff, until it is written or the time runs out.

`close` returns `Ok` only when every sink wrote everything queued before the call, never lost an entry since it
was added, and closed cleanly, so its result alone can decide an exit status. Dropping an entry is a failure,
whatever dropped it: a full queue, a `Drop` write, a line a `FileSink` could not write again, or an earlier close
that ran out of time. A sink you removed with `configure` that dropped entries counts too, after it was closed.
Otherwise it returns `CloseError`, whose `errors` map the name of each sink that failed to its `SinkError`s, in
this order:

- `TimedOut(pending)` when the time ran out. `pending` counts every entry not yet written, a write still running
  included. The others are dropped. The running write may yet land, and the sink is closed once it returns.
- The sink's own error when its close failed, such as a `FileSink` that could not write lines again after its file
  was deleted, or an error when the warning for entries the sink dropped could not be written.
- `Lost(count)` when `count` entries were lost under that name since it was first added, a sink removed and added
  back included. Every close reports the whole count, a loss an earlier flush or close reported included. The
  entries a timed-out close drops are counted in its `TimedOut`, and in `Lost` from the next close on.

Each name is one entry: a sink removed while still draining, or one that lost entries before it was removed, adds
into the entry of its name, while each sink's own [`health`](#watching-a-sinks-health) counts only what it dropped.
Its list then has one `TimedOut` and one `Lost`, each summed over those sinks. Look a sink up by its name:

```kotlin
Log.close().onError { failed ->
    failed.errors["file:app.log"]?.let { errors -> System.err.println("app.log: $errors") }
}
```

`health.dropped` counts each dropped entry once, as it is dropped, whatever dropped it, a close that ran out of
time included. A `TimedOut(pending)` also counts a write still running, which is dropped only if it then fails, so
`pending` can be one more than what that close added to `health.dropped`.

The sinks stay configured and take the next entry, so call `close` to shut down, not to stop logging. To stop logging
to a sink, remove it with `configure`. The logger has no exit hook of its own: on the JVM and native, entries
still queued when the process ends are lost.

The workers never keep a JVM or Android process alive: each sink runs on its own view of `Dispatchers.IO`, whose
threads are daemon threads. On JS and Wasm they run on the event loop, where a sink waiting out a `Retry` backoff
holds a timer that keeps Node.js running until it fires.

| call                 | waits for                              | returns                                            |
|----------------------|----------------------------------------|----------------------------------------------------|
| `Log.flush()`        | every queue, blocking                  | `Flushed`, or `FlushError.Lost(lost, stillQueued)` |
| `Log.awaitFlushed()` | every queue, suspending                | `Flushed`, or `FlushError.Lost(lost, stillQueued)` |
| `Log.close()`        | every queue, blocking, then closes     | `Unit`, or `CloseError(errors)` by sink name       |
| `Log.awaitClosed()`  | every queue, suspending, then closes   | `Unit`, or `CloseError(errors)` by sink name       |
| `sink.close()`       | that queue, blocking, then closes it   | `Unit`, or `CloseError` with that sink alone; in it `ClosedAlready` once removed and closed |

`flush(timeout)` waits the same way, five seconds by default, for configured and removed sinks alike, and keeps
every entry queued. It fails only when a sink lost entries, with `FlushError.Lost(lost, stillQueued)`. `lost` says
how many entries each sink name lost since the last flush or close. `stillQueued` maps each sink name still holding
entries to its `Pending`, as `StillQueued` below does, and is empty when every sink wrote everything. Both are keyed by
sink name, and each name is one entry: a sink removed while still draining, or one that lost entries before it was
removed, adds into the entry of its name:

```kotlin
Log.flush().onError { error ->
    when (error) {
        is FlushError.Lost -> error.lost.forEach { (name, count) -> System.err.println("$name lost $count") }
        FlushError.CannotWait -> Unit
    }
}
```

A flush reports each loss once; the next flush reports only what was lost after it, while `close` still counts it.
A close resets it too: a flush after a close reports only what was lost since that close. Otherwise flush succeeds
with one of two `Flushed` values:

- `FullyWritten` when every sink wrote everything queued before the call.
- `StillQueued(pending)` when some sinks still hold entries, nothing lost. Each name has a `Pending`:
  `GaveUp(count, error)` for a `Retry` sink whose write failed while flush waited, which ends the wait for that
  sink at once, or `TimedOut(count)` for a sink still writing when the time ran out. The sink writes them as its
  `onFailure` says. When a removed sink still draining shares the name, the counts add up, and the entry is a
  `GaveUp` if any of them gave up, with the configured sink's error before a removed one's.

A test or an audit that needs every entry written compares with `Ok(Flushed.FullyWritten)`. `isError()` is not
that check: it is false for `StillQueued`, where nothing is lost but some entries are not written yet.

```kotlin
val everythingWritten = Log.flush() == Ok(Flushed.FullyWritten)
```

Called from inside a sink's own write, `flush()` and `close()` do not wait for that sink. They report it as
pending at once, and `close()` leaves it open. A sink removed with `configure` from inside its own write is
removed at once, and closed once that write returns.

JS and Wasm have one thread, so `flush()` and `close()` cannot wait there. `flush()` ends a hold and returns
`FlushError.CannotWait` at once. `close()` drops whatever is still queued and closes each sink. Call
`awaitFlushed()` and `awaitClosed()` instead, which work on every target.

In a test, record entries with `TestSink` from [`kern:logger-test`](../logger-test/README.md), and test a sink of
your own by wrapping it in its `RecordingSink`. `assertSuccess()` comes from
[`kern:result-test`](../result/README.md#testing):

```kotlin
@Test
fun `the scan logs its count`() = runTest {
    val sink = TestSink()
    val logger = Logger()
    logger.configure { LoggerConfig(listOf(ConfiguredSink(sink, SinkConfig(level = LogLevel.VERBOSE)))) }
        .assertSuccess()

    Scanner(logger.tag("Scanner")).scan()

    assertEquals(412, sink.awaitEntry().fields["count"])
}
```

`awaitEntry()` waits in real time, even under `runTest`'s virtual clock, and so do `awaitFlushed()` and
`awaitClosed()`. All three suspend: a test that runs on threads instead of in `runTest` wraps them in
`runBlocking { }`, as [`kern:logger-test`](../logger-test/README.md#tests-on-threads) shows.

## The log file

`FileSink` lives in [`logger-file`](../logger-file/README.md), which covers rotation, deletion, failures and the
Windows notes.

## Line formats

`LogFormat.TEXT`, the default, is one line for a person, with a stack trace on the lines below it:

```
<time> <LEVEL> <tag> - <message> key=value ... error=...
```

A tag longer than 35 characters is cut. `LogFormat.JSON` is one object per line, whatever is in the message, so a
shipper reads the file a line at a time. Its keys are `timestamp`, `tag`, `level`, `message`, `fields`,
`stackTrace` and `error`; the last three are left out when the entry has none.

Both timestamps are ISO 8601. Text uses local time to the millisecond with its offset from UTC, for reading
alongside `journalctl`. JSON uses UTC with nine fraction digits, so its stamps sort as text.

## Platform notes

**Colour.** `StdoutSink` and `StderrSink` take a `ColorMode`, and `PlatformSink` uses `AUTO`. `AUTO` colours a line
when the stream is a terminal, and the environment overrides that, first match wins:

1. `NO_COLOR` set and not empty: no colour.
2. `FORCE_COLOR` or `CLICOLOR_FORCE` set, not empty and not `0`: colour, even into a pipe or under `TERM=dumb`.
3. `CLICOLOR=0` or `TERM=dumb`: no colour.

`ALWAYS` colours a pipe too, which CI logs usually want, and `NEVER` never colours. Each stream is
asked on its own, so `app 2>err.log` stays plain while standard output is a terminal. A JSON line and the log file
are never coloured. The rules are [`kern:terminal`'s](../terminal/README.md#colour), so a CLI and its logger agree.

**The JVM** can only ask whether stdin and stdout are both terminals. There standard output is coloured only when
stdin is a terminal too, and standard error only under `FORCE_COLOR`. Console lines go to the process's own file
descriptors, so `System.setOut` does not capture them: test what you log with a sink of your own.

**Windows** consoles are switched to virtual-terminal processing and UTF-8 before the first console line.

**A closed pipe ends a native process.** On Linux and macOS, once the reader of a pipe goes away
(`app | head -1`), the next console line ends the process with `SIGPIPE`. To keep running, ignore `SIGPIPE` at
the start of `main`. The console sink then fails with `SinkError.Dropped`, carrying a `WriteError`, and the other
sinks keep every entry.

**Time zone.** Each sink looks up the local zone once, on its first entry, whatever its format. On Linux and macOS
the C library supplies it, so `TZ` means what it means to `date`, daylight saving rules included. The JVM reads
`TZ` its own way. Windows, JS and Wasm use the system zone, and a zone that cannot be named gives UTC.

**Android.** Logcat stamps its own time and level, so `PlatformSink` gives it the message, fields and error, and
ignores `Timestamp`. `WTF` is logged at `ASSERT`, without calling `Log.wtf`, which can end the process. Add a
[`FileSink`](../logger-file/README.md) to get JSON on Android.

**iOS and macOS apps.** `PlatformSink` writes to the unified log, so release builds show up in Console.app and in
device logs. Each entry goes under your bundle identifier as the subsystem, with its tag as the category.
`VERBOSE` and `DEBUG` map to `DEBUG`, `WARN` to `DEFAULT`, and `WTF` to `FAULT`. The unified log keeps `DEBUG`
entries in memory only, so they show only in a live Console.app stream with debug messages included. `INFO`
entries show in a live stream with info messages included, and are not saved by default. `WARN` and above are
saved and show without any setting. The text is marked public, so a release build does not redact it as
`<private>`. A command-line tool has no bundle identifier, so it prints to standard output instead.

**JS and Wasm.** `PlatformSink` is the only built-in sink. Its lines are never coloured, since a browser console
shows ANSI codes as text.

## License

[Apache-2.0](../LICENSE).
