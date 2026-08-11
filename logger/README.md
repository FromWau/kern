# logger

A logger for Kotlin Multiplatform that you reconfigure while it runs.

Every app logs before it has read its config, which is exactly when the log level is still unknown. Those
entries are held rather than filtered, and replayed once you say what the level is. From then on the
runtime state is live: change the level and the next line obeys it, with no re-initialization step.

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

KMP consumers put it in `commonMain`. The JVM and Android artifacts are Java 25 bytecode, so those two
need a JDK 25 toolchain; the native targets have no such requirement.

`kotlinx-coroutines-core` and `kotlinx-io-core` land on your compile classpath, because `StateFlow` and
`Path` are part of the API you call. `atomicfu`, `kotlinx-datetime` and `kotlinx-serialization-json` come
along at runtime only, and nothing in the API mentions them.

## The startup problem

The order every app runs in is: start, read config, learn the log level. Anything logged in step one is
logged before you know whether to keep it.

Filtering it against a default loses the debug output you turned on. Keeping all of it means a `--quiet`
run still prints startup noise. So the logger does neither until it is told:

```kotlin
Log.tag("Startup").d { "looking for a config file" }   // held, not printed
Log.tag("Startup").i { "config loaded" }               // held, not printed

Log.configure(LoggerRuntimeState(level = LogLevel.DEBUG))
// both lines print now, with the timestamps they were logged at
```

Configure at `INFO` instead and the first line is discarded on replay, having cost nothing.

Held entries are capped at 1024, oldest dropped first, so a program that never calls `configure` leaks
nothing.

## Changing it while it runs

`LoggerRuntimeState` is the whole configuration, and it is live. A settings screen writes to it and the
effect is immediate:

```kotlin
Log.update { it.copy(level = LogLevel.VERBOSE) }
```

Read `Log.state` to render the current setting, or collect it to react to one. It is a `StateFlow` and
never a `MutableStateFlow`: writing goes through `configure` and `update` alone, so your config file stays
the single authority and the logger cannot drift from it.

```kotlin
val level: LogLevel? = Log.state.value?.level   // null until the first configure
```

| field     | what it does                                                                    |
|-----------|---------------------------------------------------------------------------------|
| `level`   | the threshold; anything below it is dropped before its message is built         |
| `format`  | `TEXT` for a person, `JSON` for a log shipper                                   |
| `console` | whether to write to the platform console                                        |
| `color`   | whether the console line is wrapped in ANSI colour                              |
| `file`    | the file to append to, or null for no file logging                              |

## Logging

`tag` names the source, and the level methods take the message as a lambda so a filtered entry never pays
to build one:

```kotlin
private val log = Log.tag("Scanner")

log.i { "scan complete" }
log.e(cause) { "scan failed" }
```

Add structured pairs when a value matters more than the sentence around it. They become a `fields` object
in JSON, and `key=value` after the message in text:

```kotlin
log.i("count" to 412, "ms" to 1200) { "scan complete" }
```

```
2026-08-11 12:34:56.789 INFO    Scanner - scan complete count=412 ms=1200

{"timestamp":"2026-08-11T12:34:56.789Z","tag":"Scanner","level":"INFO",
 "message":"scan complete","fields":{"count":"412","ms":"1200"}}
```

A JSON entry is always one line, whatever is in the message, so a shipper can read the stream a line at a
time. Text uses your local wall clock for reading alongside `journalctl`; JSON uses UTC for a machine to
sort.

A throwable goes in as a throwable, not as text you flattened first. The stack trace renders under the
message, and a `LogSink` still receives the original:

```kotlin
log.w(timeout, "attempt" to 3) { "retrying" }
```

Use `Logger()` directly instead of `Log` when one process needs more than one, or when you want sinks.

## The log file

You hand over a path that is already finished, and kern opens exactly that. It never expands a `~` and
never picks a directory for you, because where an app keeps its files is the app's decision:

```kotlin
Log.configure(
    LoggerRuntimeState(
        level = LogLevel.INFO,
        file = Path(stateDir, "app.log"),
    ),
)
```

Missing parent directories are created. Point `file` somewhere else later and the next line lands there,
so a config reload can move the log without a restart. Set it to null to stop writing one.

Each line is flushed as it is written, so a crash keeps everything up to the last entry. `close()` releases
the handle at shutdown; logging again reopens it.

If a write fails the logger reports it on the console and retries the next entry, which recovers a full
disk or a deleted directory once you fix it. That report appears even with `console = false`, since a
logger that silently stops writing is worse than an unexpected line.

## Sinks

The console and the file are built in. A `LogSink` is anything else: a crash reporter, an in-app log
viewer, a platform facility kern does not use.

```kotlin
val logger = Logger(
    sinks = listOf(
        LogSink { entry, _ ->
            if (entry.level == LogLevel.ERROR) crashReporter.record(entry.message, entry.throwable)
        },
    ),
)
```

A sink is handed the `LogEntry`, not a rendered line, so it reads `fields` and `throwable` as data instead
of parsing them back out of text. It runs on the thread that logged and holds the logger's lock, so hand
work to your own queue rather than blocking. A sink that throws is caught and reported, never surfaced at
the call site that logged.

## Per-platform consoles

| platform            | goes to                                                     |
|---------------------|-------------------------------------------------------------|
| JVM, Linux, Windows | stdout, with ANSI colour when `color` is on                 |
| Android             | logcat at the matching severity                             |
| macOS, iOS          | stdout                                                      |

Android is the one that ignores `format` and `color`, because logcat carries the tag, severity and
timestamp itself; it is given the message and fields alone. Set a `file` to get JSON on Android.

Apple targets print rather than calling `NSLog`, which would stamp a second timestamp and process name
onto a line that already has one, and would split JSON across lines. Add a sink if you want `os_log`.

## License

[Apache-2.0](../LICENSE).
