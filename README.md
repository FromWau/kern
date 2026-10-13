# kern

Small, independently consumable Kotlin Multiplatform modules for the things every project ends up needing. Each
module publishes on its own, so you take the one you want without dragging the rest onto your classpath.

Something enters kern once two real projects already need it, never because one might.

## Modules

| module                       | what it is                                                                         | pulls in                              |
|------------------------------|------------------------------------------------------------------------------------|---------------------------------------|
| [`result`](result/README.md) | a typed-error `Result<S, E>`: an expected failure is a value, not an exception     | nothing on JVM/Android, serialization-core on native |
| [`result-test`](result/README.md#test-it) | test assertions for a `Result`: `assertSuccess()` and `assertError<E>()` | `result`, kotlin-test |
| [`terminal`](terminal/README.md) | stdout/stderr, tty and width detection, and one ANSI colour policy that honours `NO_COLOR` | `result` |
| [`logger`](logger/README.md) | a logger that works with defaults at once, reconfigures live, and can hold startup entries for your config | coroutines, kotlinx-io, `terminal` (not on JS/Wasm), 3 more runtime |
| [`logger-async`](logger-async/README.md) | writes logger entries on a background writer, so the thread that logs never waits for the disk | `logger` |
| [`dirs`](dirs/README.md) | where an app's config, data, state, cache and temp belong, as `Path`s | kotlinx-io, `result` |
| [`fs`](fs/README.md) | `Path` helpers that return typed errors: `dir / "name"`, read, write by rename or in place, list, walk, move, delete and lock | `dirs` |
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

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64; `logger` also js and wasmJs.

Licensed under [Apache-2.0](LICENSE).
