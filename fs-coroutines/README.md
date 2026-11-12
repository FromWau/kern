# fs-coroutines

The file lock from [`kern:fs`](../fs/docs/locking.md) blocks the thread while it waits. In coroutine code you would
rather suspend. This module gives you the same lock with a suspending wait, and a flow that tells you when a file
changes.

- `awaitLock` and `awaitSingleInstance` wait for another holder by suspending.
- `FileWatcher(path).collect { }` tells you each time a file may have changed, every write included.
- `changes()` tells you each time a file's info changes.
- `textChanges()` answers the file's text each time it changes.
- A `watch`, which every call names, says how a change is noticed: the system's watch, a lookup on a timer for a
  network share, or a read on a timer for `/proc` and `/sys`.

```kotlin
import com.fromwau.kern.fs.coroutines.awaitLock
import com.fromwau.kern.fs.div
import com.fromwau.kern.result.onError

(dirs.state / ".sync.lock").awaitLock { syncNow() }.onError { warn(it) }
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
dependencies {
    implementation("com.fromwau.kern:fs-coroutines:$kernVersion")
}
```

- Releases are published to
  [maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/fs-coroutines). Reading it needs no
  credentials.
- It brings `kern:fs` and kotlinx-coroutines with it, since `changes()` returns a `Flow`.
- Needs what [every kern module needs](../README.md#requirements).

### API

```kotlin
suspend fun <T> Path.awaitLock(
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend () -> T,
): Result<T, FileError>
suspend fun <T> Writer.awaitLock(
    name: String,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend () -> T,
): Result<T, FileError>
suspend fun <T> AppDirs.awaitSingleInstance(
    name: String,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend (instance: InstanceLock) -> T,
): Result<T, FileError>
suspend fun <T> AppDirs.awaitSingleInstance(
    key: Path,
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: suspend (instance: InstanceLock) -> T,
): Result<T, FileError>
class FileWatcher(val path: Path, val watch: Watch = Watch.System()) {
    val changes: Flow<Result<FileInfo, FileError>>
    val metadata: Flow<Result<FileInfo, FileError>>
    fun text(maxBytes: Long, trusted: Boolean = false): Flow<Result<String, FileError>>
    suspend fun collect(action: suspend (Result<FileInfo, FileError>) -> Unit)
}
fun Path.changes(watch: Watch): Flow<Result<FileInfo, FileError>>
fun Path.textChanges(
    maxBytes: Long,
    trusted: Boolean = false,
    watch: TextWatch,
): Flow<Result<String, FileError>>
sealed interface TextWatch {
    data class Read(val every: Duration) : TextWatch
}
sealed interface Watch : TextWatch {
    data class System(val fallback: Duration = 1.seconds) : Watch
    data class Poll(val every: Duration = 1.seconds) : Watch
}
```

## Rules

`awaitLock` and `awaitSingleInstance` follow the first seven rules. `FileWatcher`, `changes()` and `textChanges()`
follow the last four, whatever their `watch`.

- `awaitLock` takes the lock `withLock` takes, so the two keep each other out, in one process or across processes.
- Each attempt does a little blocking file I/O on the calling thread. Only the wait between attempts is free of it.
- The block may suspend while it holds the lock. It may also resume on another thread.
- The lock is released when the block returns, throws or is cancelled. What the block throws is rethrown once the lock
  is released.
- A wait that is cancelled holds nothing.
- A holder that keeps the lock past `wait` is `LockBusy`. [Locking](../fs/docs/locking.md#rules) bounds `wait`, the
  default included.
- Everything else is as [Locking](../fs/docs/locking.md) describes: lock a sibling `.lock` file and leave it in place.
- `FileWatcher(path).collect { }` answers the path's `info()` when collection starts, then again each time the file may
  have changed, for as long as you collect. Every write the system tells of is an answer, even one that leaves the
  info as it was, so the same info can come again.
- `changes()` answers the path's `info()` when collection starts. It answers again each time that answer changes, for
  as long as you collect. It is `FileWatcher(path, watch).metadata`.
- Errors are answers too. They never end the flow, but for `Unwatchable`: a `Watch` of a file on procfs or sysfs
  answers it once and ends, since no watch could follow such a file.
- `textChanges()` reads the file on each of those answers, or on a timer under `TextWatch.Read`. It answers only when
  the read differs from its last answer.

## Tasks

### Lock from a command-line tool

```kotlin
import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.current
import com.fromwau.kern.fs.coroutines.awaitLock
import com.fromwau.kern.fs.div
import com.fromwau.kern.result.*
import kotlinx.coroutines.runBlocking

fun main() = runBlocking<Unit> {
    val dirs: AppDirs = BaseDirs.current()
        .flatMap { it.forApp("mytool") }
        .getOrElse { failure -> error("no place for mytool's files: $failure") }
    (dirs.state / ".sync.lock").awaitLock { syncNow() }.onError { println("not synced: $it") }
}
```

Kotlin/Native has no `suspend fun main`. It stops with `e: Entry point can not be a suspend function.` So a tool that
builds for every target enters coroutines with `runBlocking`. `runBlocking<Unit>` keeps `main` returning `Unit`.

### Answer your app's own error from work under a lock

```kotlin
val pkg: Result<Pkg, PkgError> = result {
    cache.awaitLock("pkg.lock") { install(manifest).bind() }.bind(PkgError::File)
}
```

- `install` answers your `PkgError`. The inner `bind()` passes its failure on as it is and releases the lock.
- The outer `bind(PkgError::File)` wraps the lock's own `FileError` in your case.
- `cache` is a `Writer`, so the lock file stays below its root.

[Run work of several steps under a lock](../fs/docs/locking.md#run-work-of-several-steps-under-a-lock) and
[Answer your app's own error](../fs/docs/locking.md#answer-your-apps-own-error) say more.

### Wait for another instance

Use this when only one copy of a job may run, and a second one should wait its turn instead of giving up.

```kotlin
dirs.awaitSingleInstance("mirror", wait = 30.seconds) { runMirror() }.onError { failure ->
    val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
    println("mirror still runs as pid $pid")
}
```

It runs the block as the one instance of `name`, as
[`singleInstance`](../fs/docs/locking.md#keep-a-second-instance-of-an-app-out) keeps it. It retries while that
answers `LockBusy`. `awaitSingleInstance(key)` does the same for a path, as `singleInstance(key)` takes it.

The block gets the `InstanceLock` it holds, so work that runs long can stop once the lock file was deleted:

```kotlin
dirs.awaitSingleInstance("mirror") { instance ->
    while (instance.check() is Result.Success) mirrorOnce()
}.onError { println("mirror not run: $it") }
```

### Wait for a holder in a test

```kotlin
@Test
fun `sync waits for the other holder`() = runTest {
    withContext(Dispatchers.Default) { lockFile.awaitLock(wait = 5.seconds) { syncNow() } }.assertSuccess()
}
```

`awaitLock` waits with `delay`. Under `runTest`, `delay` runs on virtual time, so a holder on another thread or in
another process looks busy for the whole `wait`. `Dispatchers.Default`'s `delay` takes real time.

### Pick how a change is noticed

| `watch` | notices a change by | use it for |
|---|---|---|
| `Watch.System()` | the system's watch, with a lookup every `fallback` where the system refuses one | ordinary files |
| `Watch.Poll(every)` | a lookup of the file's `info()` every `every` | NFS, SMB and FUSE mounts |
| `TextWatch.Read(every)`, for `textChanges()` only | a read of the file every `every` | `/proc` and `/sys` files |

### Watch a file

```kotlin
import com.fromwau.kern.fs.coroutines.Watch
import com.fromwau.kern.fs.coroutines.changes
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess

config.changes(Watch.System()).collect { change ->
    change.onSuccess { reload() }.onError { println("$config: $it") }
}
```

Changes close together may come as one answer, so read the file when an answer comes rather than counting answers.

### Act on every write

A write can leave a file's `info()` as it was: two writes of the same size within one second on a file system that
keeps whole seconds, such as ext2. `changes()` gives no answer for the second. `FileWatcher` answers every write the
system tells of.

```kotlin
import com.fromwau.kern.fs.coroutines.FileWatcher

FileWatcher(inbox).collect { change -> change.onSuccess { process(inbox) } }
```

- The same info can come again, as for a file opened for writing and closed unchanged. Act on the file's state, not
  on the count of answers.
- `FileWatcher(path).metadata` is `changes()`, and `FileWatcher(path).text(maxBytes)` is `textChanges()`.

### Reload a file when its text changes

Most of the time you do not care that a file changed. You care that its text did. `textChanges()` gives you only that.

```kotlin
import com.fromwau.kern.fs.coroutines.Watch
import com.fromwau.kern.fs.coroutines.textChanges

config.textChanges(maxBytes = 1_048_576, trusted = true, watch = Watch.System()).collect { text ->
    text.onSuccess { load(it) }.onError { println("$config: $it") }
}
```

- It reads the file with `readText(maxBytes, trusted)` each time `FileWatcher` answers.
- It answers only when the read differs from the last answer, so a `chmod` that leaves the text as it was loads
  nothing.
- It takes a `watch` as `changes()` does.

To keep a config loaded safely, see
[Reload a config when it changes](../fs/docs/reading.md#reload-a-config-when-it-changes).

Reload a config this way, with `trusted = true`. Do not read on `changes()` behind a check of your own on
`modifiedAt`. A `chmod` that fixes a refused config, or its folder, leaves `modifiedAt` as it was, so that check
misses it. `textChanges()` reads again after it and loads the text.

### Read a /proc or /sys file on a timer

```kotlin
import com.fromwau.kern.fs.coroutines.TextWatch
import com.fromwau.kern.fs.coroutines.textChanges

Path("/proc/meminfo").textChanges(maxBytes = 65_536, watch = TextWatch.Read(2.seconds)).collect { text ->
    text.onSuccess { show(parseMemInfo(it)) }.onError { println("/proc/meminfo: $it") }
}
```

- A file in `/proc` or `/sys` keeps its size, modification time and change time while its text moves, so no
  `Watch` could follow it. A `Watch` there answers `Unwatchable` once and ends. `TextWatch.Read` reads it anyway,
  every `every`.
- It answers only when the read differs from the last answer, so a counter that did not move gives none.
- So `every` is not the time between answers. To compute a rate, divide by the time you measured between two
  answers.
- Use a `Watch` for an ordinary file. It reads only when the file changed.

### Load once before the next line runs

```kotlin
runBlocking {
    launch(start = CoroutineStart.UNDISPATCHED) { config.changes(Watch.System()).collect { load(it) } }
    serve()
}
```

A collector launched normally on the same thread as code that does not suspend sees nothing until that code suspends.

### Watch a network share

```kotlin
import com.fromwau.kern.fs.coroutines.Watch
import com.fromwau.kern.fs.coroutines.changes

share.changes(Watch.Poll(5.seconds)).collect { change -> change.onSuccess { reload() } }
```

- On NFS or SMB, inotify misses a change made on another machine.
- On a FUSE mount, it misses one the file system makes behind the mount.

`Watch.Poll` answers by the same rule as the system's watch. It looks the path up every `every` without asking the
system.

## Reference

**Waiting:**

- The pauses between attempts are those [`withLock`](../fs/docs/locking.md#reference) makes. `waited` is their sum.
- `holderPid` names the holder as `tryLock` does.
- The lock is not reentrant. An `awaitLock` on a file this process holds waits as for any other holder. Then it is
  `LockBusy` naming this process.
- A folder, FIFO, device or socket at the lock path is `NotRegularFile`.
- On a [`Writer`](../fs/docs/writer.md), `name` is a relative path below its root. The writer's rules keep it there.
  A name it refuses runs nothing.
- `withLock`'s block may suspend too. But `withLock` blocks the thread while it waits.

**`awaitSingleInstance`:**

- This process's pid is in the pid file while the block runs.
- The instance is released when the block returns, throws or is cancelled. `wait`, `waited` and cancelling work as
  for `awaitLock`.
- A holder that keeps it past `wait` is `LockBusy`. Its `holderPid` is the one `singleInstance` names. Where the lock
  names none, it is read from the pid file, once the wait is up and by suspending.
- Any other answer of `singleInstance` comes back at once. Examples are `InvalidName`, or `Refused` for a `state`
  folder that is not yours alone to write.
- A key that cannot be resolved is what `canonicalPath()` answers, at once.
- Take that lock file with `awaitSingleInstance` or `singleInstance` alone, as
  [Locking](../fs/docs/locking.md#reference) explains.

**`changes()`:**

- An answer differs when it is another `FileInfo`, an error where there was info or the reverse, or another error.
- A deleted file is one `NotFound`. A file made there again is info once more.
- The `info()` answer includes `changedAt`, so a `chmod`, or a write that put the old modification time back, is a
  change.
- A change to a watched folder's mode or owner gives no answer here, and `FileWatcher` answers the same info again.
  Another file written in the folder gives none in either.
- Under `Watch.Poll` a write that leaves the size and times as they were is not seen, since only a lookup tells.
- To reload a file when its content changes, use `textChanges()`. It reads the file on each answer and answers only
  when the text differs, as [Reload a file when its text changes](#reload-a-file-when-its-text-changes) shows.
- A `Watch` or `TextWatch` made with a duration that is not positive is `IllegalArgumentException`.
- A path on procfs or sysfs is `Unwatchable`, a missing one too, judged by the nearest of it and its folders that
  exists: by `statfs` natively and the file store's type on the JVM and Android. Other platforms have neither.

How it watches:

- It waits for the system to say the file or its folder changed. Then it looks the path up.
- With inotify and kqueue the file itself is watched too, so a write through a hard link in another folder is seen.
  With inotify, a watched path that is a folder answers when an entry in it is made, removed or renamed.
- A missing folder, or one deleted or moved away while watched, is waited for through a watch on the nearest folder
  above it that is there.
- A folder on the way replaced by another at its path, as `mv top top.old; mv new top` does, is found within a quarter
  second, and the watch moves to the new one.
- The system's watch is inotify on Linux, kqueue on Apple, `ReadDirectoryChangesW` on Windows, and a `WatchService`
  on Android and other JVMs.
- On the JVM on Linux from Java 22 on, it is inotify too, reached through FFM. The JDK then prints its warning about a
  restricted method once, unless your app grants native access with `--enable-native-access=ALL-UNNAMED`, or
  `Enable-Native-Access: ALL-UNNAMED` in an executable jar's manifest. A JDK that refuses the call gets the
  `WatchService`.
- A `WatchService` never tells of a folder's own change, so there the path and each watched folder are looked up at
  least every quarter second.
- When the system dropped events because too many came at once, it looks at once and sets the watch again, since a
  folder may have moved away unseen.
- Stopping the collection releases what it watched with.

Under `Watch.System` it looks every `fallback` instead, without saying so:

- on the JVM on macOS, whose `WatchService` itself polls at a pace you cannot set
- after the system refused to watch the folder, as when the inotify watch limit is reached
- once a root the path is under was missing, such as a drive not plugged in.

Symlinks:

- A symlink is watched in the folder of every link in its chain. It is also watched in the folder of the file the
  chain ends at. This goes up to the 40 links Linux follows, so an edit of a target in another folder is an answer.
- Each folder is watched once, however the chain spells it. All of them go through one system watch per collector,
  such as one inotify instance.
- The chain is found again after each answer, so any link in it pointed elsewhere moves the watch. A folder it no
  longer passes through is no longer watched.
- When the system refuses any of the folders, it looks every `fallback` instead.
- A link to a folder on the way, such as `app/current -> releases/v1`, is followed too. The watched folders' real paths
  are checked after each change and each quarter second.
- So pointing that link at `releases/v2` moves the watch there. You get an answer when the file's `info()` differs.

**Sharp edges** of `changes()`:

- Windows has no `changedAt`, so a file replaced by one of the same size and modification time gives no answer from
  `changes()` there. `FileWatcher` answers it.
- No watch sees a write by a process that keeps the file mapped into memory until it unmaps it, nor a change made on
  another machine, or below a FUSE or overlay mount.
- A `WatchService`, on Android, on a JVM on Windows, and on Linux before Java 22, misses a write through a hard link, a
  write through a memory map, and a file opened for writing and closed unchanged.
- Windows watches only folders, so a write through a hard link in a folder not watched is missed there.
- Windows refuses to rename a folder above a watched one while the watch is open, as it does for any program
  watching it.
- Inotify watches a file itself only when you may read it, so a write through a hard link in another folder to a file
  you cannot read is missed.
- Kqueue and Windows are not yet verified on their own hardware.
- The folders beyond the link's own are verified only on Linux, native and on the JVM.
- Following a link to a folder on the way is verified on Linux, native and on the JVM.

**`textChanges()`:**

- A read differs when it is other text, an error where there was text or the reverse, or another error.
- Errors compare as `FileError` compares, so two that differ only in their exception are one.
- A `Refused` holds the `FileInfo` it found, so a file that changes while it is refused is refused again.
- A deleted file is one `NotFound`. Its text comes again once it is back, also when it is the text it had.

**`textChanges()` under `TextWatch.Read`:**

- It reads when collection starts, then `every` after each read ends and you have handled its answer, if it gave
  one, so a slow read or a slow collector stretches the gap.
- The reads run on the collector's thread and block it, as under any watch. Add `flowOn` with a dispatcher for
  blocking work to read elsewhere.
- The wait between reads is a `delay`, so under `runTest` it passes on virtual time.
- A procfs or sysfs file reports size 0 and is read to its end, so `maxBytes` bounds what it gives.

## License

[Apache-2.0](../LICENSE).
