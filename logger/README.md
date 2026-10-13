# logger

A logger for Kotlin Multiplatform that you reconfigure while it runs.

It logs from the first line, with defaults: `INFO` and above, as text, to the platform's console. Configure it
and the next line obeys, with no re-initialization step. An app that logs before it has read its own config can
have those early entries held and replayed once the config says what the level is.

A destination that fails never crashes your app. A full disk, a deleted log directory or a broken sink costs
the entries that destination refused, and every one is counted where you can see it.

```kotlin
Log.tag("Scanner").i { "scan complete" }   // printed, no setup needed
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

KMP consumers put it in `commonMain`. It is built with Kotlin 2.4.20, so use that or newer. The JVM and
Android artifacts are Java 17 bytecode, so those two need JDK 17 or newer; the native targets have no such
requirement. Everything is in one package, `com.fromwau.kern.logger`; `logger-async` adds
`com.fromwau.kern.logger.async`.

`kotlinx-coroutines-core` and `kotlinx-io-core` land on your compile classpath, because `StateFlow` and
`Path` are part of the API you call. `atomicfu`, `kotlinx-datetime` and `kotlinx-serialization-json` come
along at runtime only, and nothing in the API mentions them, nor does [`kern:terminal`](../terminal/README.md),
which every target but JS and Wasm uses for the console.

## Logging before your config is read

An app that reads its log level from its own config logs before it knows that level. Filtering those lines
against the default loses the debug output the config turns on, and printing them means a `--quiet` run
still prints startup noise. Ask the logger to hold them instead, first thing in `main`:

```kotlin
Log.holdUntilConfigured()

Log.tag("Startup").d { "looking for a config file" }   // held, not printed
Log.tag("Startup").i { "config loaded" }               // held, not printed

Log.configure(LoggerConfig(level = LogLevel.DEBUG))
// both lines print now, with the timestamps they were logged at
```

Configure at `INFO` instead and the first line is discarded on replay. A held entry builds its message when it
is logged, since no level has decided against it yet.

Calling `holdUntilConfigured()` again after a `configure` starts a new hold: the live config is gone until the
next `configure`, and a `configure { }` then starts from the defaults.

At most 1024 entries are held. Past that the oldest give way, the replay starts with a warning saying how
many, and `Log.health.value.heldDropped` counts them. A hold that overflows usually means `configure` was
forgotten or the hold is not needed.

A program that ends before `configure` still keeps its startup lines, as long as it calls `close()`: that writes
the held entries with the default `LoggerConfig`, followed by a warning that `close()` came first. With nothing
held it only ends the hold, and warns about nothing. Without either call the held entries are lost when the
process ends.

**In a CLI, `configure` on every exit path, error paths included.** The default console is `Platform`, which is
standard output on the JVM and native, so a `close()` straight after a bad flag replays the startup lines into
the data a pipe reads: `tool --bad-flag | jq` gets log lines. `configure(LoggerConfig(console =
ConsoleOutput.Stderr()))` first sends them where they belong.

## Changing it while it runs

`LoggerConfig` is the whole configuration, and it is live. Hand `configure` a whole config, or a change to
the current one, and the next entry follows it:

```kotlin
Log.configure(LoggerConfig(level = LogLevel.INFO, file = FileOutput.Append(Path(stateDir, "app.log"))))
Log.configure { it.copy(level = LogLevel.VERBOSE) }   // a settings screen
```

The second form reads and replaces under one lock, so a settings screen and a config reload on another thread
cannot undo each other's change; passing a copy of `Log.state.value` instead can lose one of the two.

Either form ends a hold. While holding, the lambda starts from the default `LoggerConfig` and the held
entries are written with what it returns, so a settings screen or watchdog that runs before your config is
read ends the hold early with defaults. Call it only once the first real `configure` has happened.

The lambda runs under the logger's lock, so every thread that logs waits for it. Keep it to a `copy`, work
out the new values beforehand, and never call `configure` or wait on a thread that logs from inside it: a
`configure` made inside the lambda is replaced by what the lambda returns.

Read `Log.state` to render the current setting, or collect it to react to one. It is a `StateFlow` and
never a `MutableStateFlow`: writing goes through `configure` and `holdUntilConfigured` alone, so your config
file stays the single authority and the logger cannot drift from it.

```kotlin
val level: LogLevel? = Log.state.value?.level   // null only while holding
```

| field         | type            | default                    | what it does                                         |
|---------------|-----------------|----------------------------|------------------------------------------------------|
| `level`       | `LogLevel`      | `LogLevel.INFO`            | the threshold; anything below it is dropped unbuilt  |
| `console`     | `ConsoleOutput` | `ConsoleOutput.Platform()` | `Platform`, `Stdout`, `Stderr` or `Off`, each with its `format` and `color` |
| `file`        | `FileOutput`    | `FileOutput.None`          | `None`, or `Append(path)` with its `format` and what a failure does |
| `sinkFailure` | `SinkFailure`   | `SinkFailure.Drop()`       | what a throwing sink costs; see [Sinks](#sinks)      |

Each setting is a type whose cases carry only what that case needs, so a combination that means nothing,
such as a colour setting with the console off, cannot be written. A `when` over one is exhaustive.

The console and the file each have a `format`, `LogFormat.TEXT` by default: `TEXT` for a person, `JSON` for a
log shipper. Each is on or off on its own, so any mix works:

| want                             | `LoggerConfig(...)`                                                                                  |
|----------------------------------|------------------------------------------------------------------------------------------------------|
| text to stdout                   | the defaults: `Platform` is standard output on the JVM, Linux, Windows and in a macOS CLI             |
| JSON to stdout                   | `console = ConsoleOutput.Stdout(format = LogFormat.JSON)`                                            |
| text to a file                   | `console = ConsoleOutput.Off, file = FileOutput.Append(path)`                                        |
| JSON to a file                   | `console = ConsoleOutput.Off, file = FileOutput.Append(path, format = LogFormat.JSON)`               |
| text to stdout, JSON to the file | `console = ConsoleOutput.Stdout(), file = FileOutput.Append(path, format = LogFormat.JSON)`          |
| JSON to stdout, text to the file | `console = ConsoleOutput.Stdout(format = LogFormat.JSON), file = FileOutput.Append(path)`            |

`Stderr` takes a `format` the same way. A JSON console line is never coloured, so each line stays parseable.

```kotlin
// A CLI: text on standard error so standard output stays clean for piping, JSON in a file
Log.configure(
    LoggerConfig(
        console = ConsoleOutput.Stderr(),
        file = FileOutput.Append(Path(stateDir, "app.log"), format = LogFormat.JSON),
    ),
)

// On the JVM a terminal on standard error is coloured under ColorMode.AUTO only with FORCE_COLOR=1, since the
// JVM cannot ask whether that stream is a terminal; map a --color=always flag to ColorMode.ALWAYS.

// An audit log: a write the file refuses must not pass silently
Log.configure { it.copy(file = FileOutput.Append(auditPath, onFailure = FileFailure.Throw)) }

// Tests: a broken sink should fail loudly
Log.configure { it.copy(sinkFailure = SinkFailure.Throw) }
```

Text lines are stamped in the system time zone, looked up on each `configure`, so a change of zone shows from
the next one. On Linux and macOS the C library supplies it, so `TZ` means exactly what it means to `date`,
rules with daylight saving such as `CET-1CEST,M3.5.0,M10.5.0/3` included. The JVM reads `TZ` its own way:
`GMT+3` is three hours ahead of UTC there and behind it in C, and an empty `TZ` is the system zone there and
UTC in C. Windows uses the system zone, and a zone the platform cannot name gives UTC.

## Logging

`tag` names the source, and the level methods take the message as a lambda so a filtered entry never pays
to build one:

```kotlin
import com.fromwau.kern.logger.Log

private val log = Log.tag("Scanner")

log.i { "scan complete" }
log.e(cause) { "scan failed" }
```

Add fields when a value matters on its own, such as a count, a duration or a request id, so a log tool can
filter on it without parsing the sentence. They become a `fields` object in JSON, and `key=value` after the
message in text:

```kotlin
log.i("count" to files.size, "ms" to elapsed.inWholeMilliseconds, "cached" to true) { "scan complete" }
```

```
2026-08-11T14:34:56.789+02:00 INFO    Scanner - scan complete count=412 ms=1200 cached=true

{"timestamp":"2026-08-11T12:34:56.789123456Z","tag":"Scanner","level":"INFO",
 "message":"scan complete","fields":{"count":412,"ms":1200,"cached":true}}
```

A JSON entry is always one line, whatever is in the message, so a shipper can read the stream a line at a
time. Both stamps are ISO 8601. Text uses your local time to the millisecond with its offset, for reading
alongside `journalctl`; JSON uses UTC with all nine fraction digits, so its stamps sort as text. The
timestamp is taken when you log, before the entry waits for the logger, so under contention the file's order
can differ from timestamp order by a few milliseconds.

A JSON field carries its type in the literal, so a log tool can run a numeric query on `count`:

| value                                            | JSON                 | text           |
|--------------------------------------------------|----------------------|----------------|
| `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` | a number: `5001`     | `port=5001`    |
| `Boolean`                                        | `true` or `false`    | `tls=true`     |
| `null`                                           | `null`               | `user=null`    |
| `NaN` or an infinity                             | a string: `"NaN"`    | `ratio=NaN`    |
| anything else                                    | its `toString()`     | the same       |

A value is turned into text when the entry is written, which under a hold or `AsyncDelivery` is later and on
another thread, so pass a value that will not change, such as a copy of a list rather than the list itself. A
`toString()` that throws is written as `<toString() threw IllegalStateException>` instead of losing the entry.

A null is written, not left out, so "logged but empty" stays apart from "never logged": `"user":null`, never
the four-character string `"null"`. A `Long` above 2^53 loses precision in log viewers built on JavaScript. A
key passed twice keeps its last value.

A throwable goes in as a throwable, not as text you flattened first. The stack trace renders under the
message, and a `LogSink` still receives the original:

```kotlin
log.w(timeout, "attempt" to "3") { "retrying" }
```

Every entry is written on the thread that logged, before the call returns, which costs a few microseconds
with a file. [`kern:logger-async`](../logger-async/README.md) moves the writing to a background writer, for a
thread that must stay smooth: `Logger(delivery = AsyncDelivery())`.

Use `Logger()` directly instead of `Log` when one process needs more than one, when you want sinks, or when you
want `AsyncDelivery`: `Log` has no sinks and always writes on the thread that logged. A `Logger` has everything
`Log` has: `configure`, `holdUntilConfigured`, `state`, `health`, `tag` and `close`. Its `log(tag, level,
throwable, fields) { message }` takes the level as a value, for code that forwards entries from elsewhere.

Each entry reaches a sink as a `LogEntry`: `timestamp` (a `kotlin.time.Instant`), `tag`, `level`, `message`,
`fields` (a `Map<String, Any?>`, values as logged) and `throwable`.

## The log file

You hand over a path that is already finished, and kern opens exactly that. It never expands a `~` and
never picks a directory for you, because where an app keeps its files is the app's decision:

```kotlin
Log.configure(
    LoggerConfig(
        level = LogLevel.INFO,
        file = FileOutput.Append(Path(stateDir, "app.log")),
    ),
)
```

Missing parent directories are created. Point `file` somewhere else later and the next line lands there,
so a config reload can move the log without a restart. Set it to `FileOutput.None` to stop writing one.

Each line is written to the file in one append as it is logged, so a crash keeps everything up to the last
entry, and two processes logging to the same file never split each other's lines. `close()` releases the
handle at shutdown; logging again reopens it.

The open file is kept while `file` stays the same. A log that is deleted or rotated away keeps being written
to the old file until you call `close()`, so call it from your rotation hook to start a fresh file at the
path. Call it from an ordinary thread, not from inside a native signal handler, since it waits for the logger's
lock. A `copytruncate` rotation needs no hook, because every write appends.

A full disk or a deleted directory is your environment, not a bug in your app, so it never crashes it.
`Append(path, onFailure = ...)` decides what happens to an entry the file refuses:

| `onFailure`                | a refused entry                                              | the code that logged |
|----------------------------|--------------------------------------------------------------|----------------------|
| `FileFailure.Retry()`      | held in memory and written, in order, once the file recovers | never sees it        |
| `FileFailure.Drop`         | lost, and counted                                            | never sees it        |
| `FileFailure.Throw`        | lost, and counted                                            | gets the exception   |

`Retry` is the default. It holds up to `capacity` entries (1000), and when they run out `drop` decides which
end gives way: `DropPolicy.OLDEST` keeps recent context, `DropPolicy.NEWEST` keeps the lead-up to the failure.
While the file refuses, it tries again at most once per `backoff` (one second), so a dead disk costs one failing
write per second rather than one per entry. When the file takes writes again after entries were dropped, a
line in their place says how many and when:

```
2026-08-11T14:05:40.112+02:00 WARN    kern.logger - dropped 312 entries the log file refused, logged between 2026-08-11T12:02:11.034Z and 2026-08-11T12:05:40.101Z dropped=312
```

The console and the sinks get every entry either way, and never wait for the file. `Throw` is for an audit
log, where a lost entry is itself a failure; the exception reaches your call only after the console and the
sinks have the entry.

The first failure of each file is reported in one line on standard error; later ones only count. `close()`
makes one last attempt at the held entries, and reports what it still could not write in a single line rather
than one per entry.

## Sinks

The console and the file are built in. A `LogSink` is anything else: a crash reporter, an in-app log
viewer, a platform facility kern does not use.

```kotlin
val logger = Logger(
    sinks = listOf(
        LogSink { entry, _ -> crashReporter.record(entry.message, entry.throwable) }.atLeast(LogLevel.ERROR),
    ),
)
```

`atLeast` offers the sink only entries at that level or above. Filter that way rather than with an `if` inside
the sink: an entry the sink returns from at once counts as a success, which resets the breaker below, so a
crash reporter that is down would never be skipped while routine entries keep arriving.

A sink is handed the `LogEntry`, not a rendered line, so it reads `fields` and `throwable` as data instead of
parsing them back out of text. The second argument is the `LoggerConfig` the entry is written under, for a
sink that follows the configured formats or `level` itself. It runs holding the logger's lock, on the thread
that logged unless you chose `AsyncDelivery`, so hand work to your own queue rather than blocking.

A sink that throws costs that sink the entry, and nothing else: the other sinks, the file and the console
still get it, and the line that logged never sees the failure. `sinkFailure` decides:

- `SinkFailure.Drop()`, the default, drops the entry for that sink and counts it. Its `breaker` stops calling a
  sink that keeps failing, so one that times out on every call cannot slow every line down:
  `Breaker.Trip(consecutiveFailures = 10, cooldown = 60.seconds)` skips the sink after ten failures in a row,
  and tries it again once a minute until one call succeeds. `Breaker.Off` offers every entry regardless.
- `SinkFailure.Throw` throws the sink's exception to the line that logged, after everything else has the
  entry. Use it in tests, where a broken sink should fail loudly. Under `AsyncDelivery` it cannot reach that
  line, so the logger reports it once on standard error instead and `delivery.health` counts it: test a sink
  with the default inline delivery.

Only an `Exception` is handled, a `CancellationException` included: `write` does not suspend, so one thrown
there is the sink's own, such as a timeout inside its `runBlocking`. An `Error` such as `OutOfMemoryError`
propagates untouched.

A sink without durability of its own, such as one posting to your own HTTP endpoint, can be wrapped to hold
and retry what it refuses instead of dropping it, with the same capacity, drop policy and backoff as the file:

```kotlin
val logger = Logger(sinks = listOf(RetryingSink(httpSink.atLeast(LogLevel.ERROR), capacity = 500, backoff = 5.seconds)))
```

It retries when the next entry for it arrives after `backoff` has passed; there is no timer of its own. It holds
only the levels its sink takes, so put `atLeast` inside it, as above, and routine entries never take a slot.
`close()` makes one last attempt and reports in one line what it still could not deliver. A crash reporting SDK
that already persists and retries does not need it.

Every entry reaches the file, then the sinks in list order, then the console. An entry a sink logs from
inside `write` goes all the way through first, to every destination except that sink: it never comes back to
the sink that logged it, so a failing sink that logs its own failure cannot loop.

## Watching for failures

`health` counts what each destination failed to take, and changes only when something fails, recovers or is
dropped, so it costs nothing to collect while all is well:

```kotlin
Log.health.collect { health ->
    if (health.file.dropped > 0) showBanner("Logging to disk failed: ${health.file.lastError}")
}
```

`health.value` is a `LoggerHealth`:

| field         | type               | what it counts                                                           |
|---------------|--------------------|--------------------------------------------------------------------------|
| `file`        | `BufferHealth`     | the file's `failures` and `dropped` (`Long`), `buffered` (`Int`, held for a retry) and `lastError` (`Throwable?`) |
| `sinks`       | `List<SinkHealth>` | one per sink, in the order you passed them: `failures`, `dropped`, `lastError`, `breakerOpen` |
| `heldDropped` | `Long`             | entries `holdUntilConfigured` dropped because more than 1024 were held   |

A `RetryingSink` has its own `health`, a `BufferHealth` like `file`.

The logger also writes one line to standard error the first time each destination fails, and one when
`close()` gives up on what it held, whatever the configured level, since those are the cases where its own
output may be what is lost. Read `health` to show the rest in your own words.

## Per-platform consoles

`ConsoleOutput.Platform`, the default, is the platform's own log. `Stdout` and `Stderr` are always that
stream, whatever the platform.

| platform            | `Platform` goes to                         |
|---------------------|--------------------------------------------|
| JVM, Linux, Windows | standard output, colour permitting         |
| Android             | logcat at the matching severity            |
| iOS, a macOS app    | the unified log (`os_log`)                 |
| a macOS CLI         | standard output, colour permitting         |
| JS, Wasm            | `console.debug`, `info`, `warn` or `error` |

`ConsoleOutput.Stderr()` keeps standard output for a CLI's data, so `app | jq` reads only what the app printed.
On the JVM both streams are written to the process's file descriptors, not through `System.out` and
`System.err`, so `System.setOut` does not capture them: test what reaches the console with a `LogSink`.

**A closed console stream ends a native process.** On Linux and macOS, when the reader of a pipe goes away
(`app | head -1`), the next console line ends the process with `SIGPIPE`, the usual Unix behaviour for a
command-line tool. The entry is already in the file and the sinks by then. An app that has to keep running,
such as a daemon writing to a pipe, ignores `SIGPIPE` at the start of `main`; the console lines are then lost
and the file and sinks keep every entry, as on the JVM.

**A file size limit ends a native process too.** Under `ulimit -f`, systemd's `LimitFSIZE=` or a container's
limit, the write that crosses it ends the process with `SIGXFSZ`, leaving that entry torn. Ignore `SIGXFSZ` at
the start of `main` to keep running: the write then fails like a full disk, and the next entry tries again. The
JVM ignores it by itself.

**Colour follows `ColorMode`**, set on each stream case: `ConsoleOutput.Stderr(color = ColorMode.ALWAYS)`.
`AUTO`, the default, colours only when the console would accept it, so a piped or redirected run stays plain
without you doing anything, `NO_COLOR` is obeyed, and `FORCE_COLOR=1` colours a pipe anyway. `ALWAYS` colours
a pipe too, which is what CI logs usually want, and `NEVER` never colours. JSON is never coloured, and neither
is the log file. Under `AUTO` each stream is asked on its own, so `app 2>err.log` stays plain while standard
output is a terminal. The JVM can only ask whether stdin and stdout are both terminals, so there standard
output is coloured only when stdin is a terminal too, and standard error only under `FORCE_COLOR`.

Windows consoles are opted into virtual-terminal processing and UTF-8 on the first line written. That policy
lives in [`kern:terminal`](../terminal/README.md), so a CLI and its logger cannot disagree about it.

On JS and Wasm, `Stdout` is `console.log` and `Stderr` is `console.error`, which Node.js writes to those
streams. Nothing is coloured there, since a browser console shows ANSI codes as text. There is no file to
append to either: a configured `file` refuses every write, which its `onFailure` handles like any other
refusal, so leave it at `FileOutput.None` on these targets.

Android's logcat ignores the console's `format` and colour, because it carries the tag, severity and timestamp
itself; it is given the message and fields alone. Set a `file` to get JSON on Android, or choose `Stdout` to
get the rendered line under logcat's `System.out` tag.

On Apple platforms `Platform` writes to the unified log, so release builds show up in Console.app and in device
logs, not only in Xcode. Each entry goes under your app's bundle identifier as the subsystem, with its tag as
the category, at the matching type: `DEBUG` for `VERBOSE` and `DEBUG`, `INFO`, `DEFAULT` for `WARN`, and
`ERROR`. The unified log stamps its own time and level, so it gets the message, fields and stack trace rather
than the rendered line, marked public so a release build does not redact it as `<private>`. A command-line tool
has no bundle identifier, so on macOS it prints to standard output as on Linux; choose `Stdout` or `Stderr` to
print from an app too.

## License

[Apache-2.0](../LICENSE).
