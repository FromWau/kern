# logger

A logger for Kotlin Multiplatform that you reconfigure while it runs.

It logs from the first line, with defaults: `INFO` and above, as text, to the console. Configure it and the
next line obeys, with no re-initialization step. An app that logs before it has read its own config can have
those early entries held and replayed once the config says what the level is.

```kotlin
Log.tag("Scanner").i { "scan complete" }   // printed, no setup needed
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

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
Android artifacts are Java 25 bytecode, so those two need a JDK 25 toolchain; the native targets have no such
requirement. Everything is in one package, `com.fromwau.kern.logger`.

`kotlinx-coroutines-core` and `kotlinx-io-core` land on your compile classpath, because `StateFlow` and
`Path` are part of the API you call. [`kern:terminal`](../terminal/README.md), `atomicfu`,
`kotlinx-datetime` and `kotlinx-serialization-json` come along at runtime only, and nothing in the API
mentions them.

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

At most 1024 entries are held. Every entry logged past that before `configure` throws an
`IllegalStateException`, on whichever thread logs it, because a hold that never ends means `configure` was
forgotten or the hold is not needed.

A program that ends before `configure` still keeps its startup lines: `close()` writes the held entries with
the default `LoggerConfig`, to standard output, followed by a warning that `close()` came first. With nothing
held it only ends the hold, and warns about nothing. A path that ends early on purpose, such as `--help`, can
`configure` first to choose where they go.

## Changing it while it runs

`LoggerConfig` is the whole configuration, and it is live. Hand `configure` a whole config, or a change to
the current one, and the next entry follows it:

```kotlin
Log.configure(LoggerConfig(level = LogLevel.INFO, file = Path(stateDir, "app.log")))
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

| field     | type        | default          | what it does                                                 |
|-----------|-------------|------------------|--------------------------------------------------------------|
| `level`   | `LogLevel`  | `LogLevel.INFO`  | the threshold; anything below it is dropped unbuilt          |
| `format`  | `LogFormat` | `LogFormat.TEXT` | `TEXT` for a person, `JSON` for a log shipper                |
| `console` | `Console`   | `Console.STDOUT` | `STDOUT`, `STDERR` or `OFF`                                  |
| `color`   | `Boolean`   | `true`           | a ceiling on ANSI colour, not a switch; see below            |
| `file`    | `Path?`     | `null`           | the file to append to, or null for no file logging           |

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
log.i("count" to files.size.toString(), "ms" to elapsed.inWholeMilliseconds.toString()) { "scan complete" }
```

```
2026-08-11T14:34:56.789+02:00 INFO    Scanner - scan complete count=412 ms=1200

{"timestamp":"2026-08-11T12:34:56.789123456Z","tag":"Scanner","level":"INFO",
 "message":"scan complete","fields":{"count":"412","ms":"1200"}}
```

A JSON entry is always one line, whatever is in the message, so a shipper can read the stream a line at a
time. Both stamps are ISO 8601. Text uses your local time to the millisecond with its offset, for reading
alongside `journalctl`; JSON uses UTC with all nine fraction digits, so its stamps sort as text. The
timestamp is taken when you log, before the entry waits for the logger, so under contention the file's order
can differ from timestamp order by a few milliseconds.

A field value is a `String` you encode yourself, so a JSON value is always a string and never `null`, and the
line holds exactly the text you chose. A key passed twice keeps its last value.

A throwable goes in as a throwable, not as text you flattened first. The stack trace renders under the
message, and a `LogSink` still receives the original:

```kotlin
log.w(timeout, "attempt" to "3") { "retrying" }
```

Use `Logger()` directly instead of `Log` when one process needs more than one, or when you want sinks. A
`Logger` has everything `Log` has: `configure`, `holdUntilConfigured`, `state`, `tag` and `close`.

Each entry reaches a sink as a `LogEntry`: `timestamp` (a `kotlin.time.Instant`), `tag`, `level`, `message`,
`fields` (a `Map<String, String>`) and `throwable`.

## The log file

You hand over a path that is already finished, and kern opens exactly that. It never expands a `~` and
never picks a directory for you, because where an app keeps its files is the app's decision:

```kotlin
Log.configure(
    LoggerConfig(
        level = LogLevel.INFO,
        file = Path(stateDir, "app.log"),
    ),
)
```

Missing parent directories are created. Point `file` somewhere else later and the next line lands there,
so a config reload can move the log without a restart. Set it to null to stop writing one.

Each line is written to the file in one append as it is logged, so a crash keeps everything up to the last
entry, and two processes logging to the same file never split each other's lines. `close()` releases the
handle at shutdown; logging again reopens it.

The open file is kept while `file` stays the same. A log that is deleted or rotated away keeps being written
to the old file until you call `close()`, so call it from your rotation hook to start a fresh file at the
path. Call it from an ordinary thread, not from inside a signal handler, since it waits for the logger's
lock. A `copytruncate` rotation needs no hook, because every write appends.

A write that fails is dropped without a word, and the next entry tries again, which recovers a full disk or a
missing directory once you fix it. Where the log file lives and whether it can be written is your
environment, and a logger whose file is gone has nothing left to tell you through. The console and the sinks
still get every entry.

## Sinks

The console and the file are built in. A `LogSink` is anything else: a crash reporter, an in-app log
viewer, a platform facility kern does not use.

```kotlin
val logger = Logger(
    sinks = listOf(
        LogSink { entry, config ->
            if (entry.level == LogLevel.ERROR) crashReporter.record(entry.message, entry.throwable)
        },
    ),
)
```

A sink is handed the `LogEntry`, not a rendered line, so it reads `fields` and `throwable` as data instead
of parsing them back out of text. The second argument is the `LoggerConfig` the entry was logged under, for
a sink that follows `format` or `level` itself. It runs on the thread that logged and holds the logger's
lock, so hand work to your own queue rather than blocking.

A sink is your code, so what it throws reaches the line that logged, as with any other bug in your code. A
sink that calls the network or a database handles that failure itself, since it alone knows whether to
retry, queue or drop:

```kotlin
LogSink { entry, _ ->
    try {
        http.post(reportUrl, entry.message)
    } catch (e: IOException) {
        pending.add(entry)
    }
}
```

Every entry reaches the file, then the sinks in list order, then the console. An entry a sink logs from
inside `write` goes all the way through first, so it reaches the console and any later sink before the entry
that caused it.

## Per-platform consoles

| platform            | goes to                                                     |
|---------------------|-------------------------------------------------------------|
| JVM, Linux, Windows | stdout or stderr, as `console` says, colour permitting      |
| Android             | logcat at the matching severity, for either stream          |
| macOS, iOS          | stdout or stderr, as `console` says, colour permitting      |

`Console.STDERR` keeps standard output for a CLI's data, so `app | jq` reads only what the app printed.

**A closed console stream ends a native process.** On Linux and macOS, when the reader of a pipe goes away
(`app | head -1`), the next console line ends the process with `SIGPIPE`, the usual Unix behaviour for a
command-line tool. The entry is already in the file and the sinks by then. An app that has to keep running,
such as a daemon writing to a pipe, ignores `SIGPIPE` at the start of `main`; the console lines are then lost
and the file and sinks keep every entry, as on the JVM.

**A file size limit ends a native process too.** Under `ulimit -f`, systemd's `LimitFSIZE=` or a container's
limit, the write that crosses it ends the process with `SIGXFSZ`, leaving that entry torn. Ignore `SIGXFSZ` at
the start of `main` to keep running: the write then fails like a full disk, and the next entry tries again. The
JVM ignores it by itself.

**`color` is a ceiling, not a switch.** Colour is emitted only when you allow it *and* the console would
accept it, so a piped or redirected run stays plain without you doing anything, and `NO_COLOR` is obeyed.
Set `FORCE_COLOR=1` to colour a pipe anyway, which is what CI logs usually want. JSON is never coloured,
and neither is the log file. Each stream is asked on its own, so `app 2>err.log` stays plain while standard
output is a terminal. The JVM can only ask whether stdin and stdout are both terminals, so there standard
output is coloured only when stdin is a terminal too, and `Console.STDERR` only under `FORCE_COLOR`.

Windows consoles are opted into virtual-terminal processing and UTF-8 on the first line written. That policy
lives in [`kern:terminal`](../terminal/README.md), so a CLI and its logger cannot disagree about it.

Android is the one that ignores `format` and `color`, because logcat carries the tag, severity and
timestamp itself; it is given the message and fields alone. Set a `file` to get JSON on Android.

Apple targets print rather than calling `NSLog`, which would stamp a second timestamp and process name
onto a line that already has one, and would split JSON across lines. Add a sink if you want `os_log`.

## License

[Apache-2.0](../LICENSE).
