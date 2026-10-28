# kern

kern is a set of small Kotlin Multiplatform modules for the things every project ends up needing. Each module
publishes on its own. You take the one you want, and the rest stays off your classpath.

Something enters kern once two real projects already need it, never because one might.

## Modules

Pick a module from the table. The last column says what it brings along.

| module                       | what it is                                                                         | pulls in                              |
|------------------------------|------------------------------------------------------------------------------------|---------------------------------------|
| [`result`](result/README.md) | a typed-error `Result<S, E>`: an expected failure is a value, not an exception     | nothing on JVM/Android, serialization-core elsewhere |
| [`result-test`](result/README.md#testing) | test assertions for a `Result`: `assertSuccess()`, and `assertError { it is … }` checked by the compiler | `result`, kotlin-test |
| [`result-coroutines`](result-coroutines/README.md) | `suspendCatching`, flows of results (`asResult`, `mapSuccess`) and `parMapResult`, which stops at the first error | `result`, coroutines |
| [`terminal`](terminal/README.md) | stdout/stderr, reading stdin, tty and width detection, styled text, and one ANSI colour policy that honours `NO_COLOR` | `result` |
| [`terminal-test`](terminal-test/README.md) | `RecordingTerminal`: a test terminal that keeps what each stream was sent, feeds standard input, and fails a stream's writes or the read when told to | `terminal`; at runtime atomicfu |
| [`terminal-coroutines`](terminal-coroutines/README.md) | `outEach` for a `Flow`: writes it to stdout in chunks of about 64 K characters, stopping at the first failed write to stdout | `terminal`, coroutines |
| [`logger`](logger/README.md) | a logger that never throws: every destination is a sink with its own queue, so a log call never waits for a sink to write | `result`, coroutines; at runtime `terminal`, atomicfu, datetime, serialization-json |
| [`logger-file`](logger-file/README.md) | `FileSink`: a log file that survives rotation and deletion and fails with `fs`'s typed `FileError` | `logger`, `fs`; at runtime atomicfu |
| [`logger-sentry`](logger-sentry/README.md) | `SentrySink`: entries as Sentry events, breadcrumbs and logs, each from its own level | `logger`; at runtime the Sentry KMP SDK |
| [`logger-test`](logger-test/README.md) | test sinks: `TestSink` waits for what your code logs, `RecordingSink` records each write of a sink of your own | `logger`, coroutines, kotlin-test; at runtime atomicfu |
| [`dirs`](dirs/README.md) | where an app's config, data, state, cache and temp belong, as `Path`s | kotlinx-io, `result` |
| [`fs`](fs/README.md) | `Path` helpers that return typed errors: `dir / "name"`, read, write by rename or in place, append, list, walk, move, delete and lock | `dirs` |
| [`fs-test`](fs-test/README.md) | test helpers for `fs`: `scratchDir()`, and `symlinkTo`, `hardLinkTo`, `chmod` and `mkfifo` failing with `fs`'s `FileError` | `fs`, `result-test` |
| [`fs-coroutines`](fs-coroutines/README.md) | `awaitLock`: the `fs` file lock, waiting by suspending instead of blocking the thread; `changes()`: a flow of a file's `info()` each time it changes | `fs`, coroutines |
| [`process`](process/README.md) | `onShutdownRequest` or `stopOnShutdownRequest`: a handler, or a flag your loop polls, for Ctrl-C, `SIGTERM` and `SIGHUP`, then the shell's 128+N exit code | `result` |
| [`process-test`](process-test/README.md) | `FakeShutdownSource`: sends a shutdown request to your handler or stop in a test and records the exit code, without exiting | `process` |
| [`fuzzy`](fuzzy/README.md) | `didYouMean`: the name someone most likely meant by a word that is not one, and `editDistance` | nothing |

`fs-test`'s `scratchDir()` makes a fresh folder under `TMPDIR` when that is an absolute path. Otherwise it uses the
platform's temp folder.

Add a module by its name:

```kotlin
implementation("com.fromwau.kern:result:$kernVersion")
```

Published versions are listed at [maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern). You
can read it without credentials.

With `result`, your own function returns its failure as a value:

```kotlin
fun findUser(id: Long): Result<User, CrudError> = users[id].toResult { CrudError.NotFound(id) }
```

Every kern function that returns a value marks it as one to use. With Kotlin's return value checker on, a dropped
`Result` warns. `onSuccess`, `onError`, `bind`, `collect` and the `result-test` assertions are exempt. See
[must-use results](result/README.md#must-use-results).

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. Some modules add more:

- `logger`, `logger-sentry` and `logger-test` add js and wasmJs.
- `terminal`, `terminal-test` and `terminal-coroutines` add linuxArm64, the four androidNative targets, js, wasmJs
  and wasmWasi.
- `result` and `result-test` add those and tvOS and watchOS too.
- `process` and `process-test` have `terminal`'s targets without macOS and iOS. Those are not built yet.

Each module's README lists its own.

## Requirements

- Kotlin 2.4.20 or newer.
- The JVM and Android artifacts are Java 17 bytecode, so they need JDK 17 or newer.
- Android needs `minSdk` 26 and `compileSdk` 37.
- The native and web targets have no such requirement.

## License

Licensed under [Apache-2.0](LICENSE).
