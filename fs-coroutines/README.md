# fs-coroutines

The file lock from [`kern:fs`](../fs/README.md) for coroutine code: `awaitLock` waits for another holder by
suspending instead of blocking the thread. Each attempt still does a little blocking file I/O on the calling thread;
only the wait between attempts is free of it.

```kotlin
import com.fromwau.kern.fs.coroutines.awaitLock

(dirs.state / ".sync.lock").awaitLock { syncNow() }   // Result<T, FileError>
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

Releases are published as `com.fromwau.kern:fs-coroutines` to
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/fs-coroutines), which needs no
credentials to read. It brings `kern:fs` with it, and kotlinx-coroutines at runtime.

```kotlin
dependencies {
    implementation("com.fromwau.kern:fs-coroutines:$kernVersion")
}
```

The JVM and Android artifacts are Java 17 bytecode, so those two need JDK 17 or newer.

## How it behaves

It is the lock `withLock` takes, so the two keep each other out, in one process or across processes. The block
may suspend, and resume on another thread, while the lock is held. The lock is released when the block returns,
throws or is cancelled, and a wait that is cancelled holds nothing.

A holder that keeps the lock past `waitMillis`, half a second unless you pass another, is `LockBusy`, whose
`waitedMs` counts the pauses between attempts. Unlike `withLock` on native Linux and Apple, it never names the
holder's process. Everything else, such as locking a sibling
`.lock` file and leaving it in place, is as [`fs`](../fs/README.md) describes.

## License

[Apache-2.0](../LICENSE).
