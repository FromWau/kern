# kern

Small, independently consumable Kotlin Multiplatform modules for the things every project ends up
needing. Each module publishes on its own, so you take the one you want without dragging the rest onto
your classpath.

Something enters kern once two real projects already need it, never because one might.

## Modules

| module                       | what it is                                                                     | pulls in |
|------------------------------|--------------------------------------------------------------------------------|----------|
| [`result`](result/README.md) | a typed-error `Result<S, E>`: an expected failure is a value, not an exception | nothing  |

```kotlin
implementation("com.fromwau.kern:result:$kernVersion")
```

Published versions are listed at
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern), which reads without
credentials.

```kotlin
fun findUser(id: Long): Result<User, CrudError> =
    users[id]?.let { Ok(it) } ?: Err(CrudError.NotFound(id))
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

Licensed under [Apache-2.0](LICENSE).
