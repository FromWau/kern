# kern

Small, independently consumable Kotlin Multiplatform modules for the things every project ends up needing. Each
module publishes on its own, so you take the one you want without dragging the rest onto your classpath.

Something enters kern once two real projects already need it, never because one might.

## Modules

| module                       | what it is                                                                         | pulls in                              |
|------------------------------|------------------------------------------------------------------------------------|---------------------------------------|
| [`result`](result/README.md) | a typed-error `Result<S, E>`: an expected failure is a value, not an exception     | nothing on JVM/Android, serialization-core elsewhere |
| [`result-test`](result/README.md#testing) | test assertions for a `Result`: `assertSuccess()`, and `assertError { it is … }` checked by the compiler | `result`, kotlin-test |
| [`result-coroutines`](result-coroutines/README.md) | `suspendCatching`, flows of results (`asResult`, `mapSuccess`) and `parMapResult`, which stops at the first error | `result`, coroutines |
| [`terminal`](terminal/README.md) | stdout/stderr, tty and width detection, and one ANSI colour policy that honours `NO_COLOR` | `result` |
| [`logger`](logger/README.md) | a logger that never throws: every destination is a sink with its own queue, so a log call never waits for a sink to write | `result`, coroutines; at runtime `terminal`, atomicfu, datetime, serialization-json |
| [`logger-file`](logger-file/README.md) | `FileSink`: a log file that survives rotation and deletion and fails with `fs`'s typed `FileError` | `logger`, `fs`; at runtime atomicfu |
| [`logger-sentry`](logger-sentry/README.md) | `SentrySink`: entries as Sentry events, breadcrumbs and logs, each from its own level | `logger`; at runtime the Sentry KMP SDK |
| [`logger-test`](logger-test/README.md) | test sinks: `TestSink` waits for what your code logs, `RecordingSink` records each write of a sink of your own | `logger`, coroutines, kotlin-test; at runtime atomicfu |
| [`dirs`](dirs/README.md) | where an app's config, data, state, cache and temp belong, as `Path`s | kotlinx-io, `result` |
| [`fs`](fs/README.md) | `Path` helpers that return typed errors: `dir / "name"`, read, write by rename or in place, append, list, walk, move, delete and lock | `dirs` |
| [`fs-coroutines`](fs-coroutines/README.md) | `awaitLock`: the `fs` file lock, waiting by suspending instead of blocking the thread | `fs`, coroutines |
| [`fuzzy`](fuzzy/README.md) | `didYouMean`: the name someone most likely meant by a word that is not one, and `editDistance` | nothing |

```kotlin
implementation("com.fromwau.kern:result:$kernVersion")
```

Published versions are listed at
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern), which reads without
credentials.

```kotlin
fun findUser(id: Long): Result<User, CrudError> = users[id].toResult { CrudError.NotFound(id) }
```

Every kern function that returns a value marks it as one to use, so with Kotlin's return value checker on, a
dropped `Result` warns; `onSuccess`, `onError`, `bind`, `collect` and the `result-test` assertions are exempt (see
[must-use results](result/README.md#must-use-results)).

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. `logger`, `logger-sentry`
and `logger-test` add js and wasmJs. `terminal` adds linuxArm64, the four androidNative targets, js, wasmJs and
wasmWasi. `result` and `result-test` add those and tvOS and watchOS too. Each module's README lists its own.

## Requirements

- Kotlin 2.4.20 or newer.
- The JVM and Android artifacts are Java 17 bytecode, so they need JDK 17 or newer.
- Android needs `minSdk` 26 and `compileSdk` 37.
- The native and web targets have no such requirement.

## License

Licensed under [Apache-2.0](LICENSE).
