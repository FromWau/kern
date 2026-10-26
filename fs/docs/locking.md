# Locking

Two runs of the same work at once trip over each other. A cron job starts while the last one is still going,
or a user opens the app twice. A lock file keeps them apart, across threads and processes.

- Use `withLock` around a block.
- Use `tryLock` to hold a lock for a whole run.
- Use `singleInstance` for an app or task that must not run twice.

Part of [`fs`](../README.md).

```kotlin
val DEFAULT_LOCK_WAIT: Duration
inline fun <T> Path.withLock(
    wait: Duration = DEFAULT_LOCK_WAIT,
    block: () -> T,
): Result<T, FileError>
fun Path.tryLock(): Result<FileLock, FileError>
fun AppDirs.singleInstance(name: String): Result<InstanceLock, FileError>
fun AppDirs.singleInstance(key: Path): Result<InstanceLock, FileError>
fun currentProcessId(): Long
```

## Rules

- `lockFile.withLock { work() }` runs the work holding an exclusive lock, so two runs never overlap, whether they are
  threads of one process or two processes, JVM and native alike.
- The lock is advisory. It keeps out other `withLock`, `tryLock` and `awaitLock` calls and nothing else.
- Waiting is bounded. A holder that keeps the lock past `wait`, half a second by default, makes the call `LockBusy`.
- A `wait` of zero or less tries once.
- What the work throws is rethrown once the lock is released. That holds when it is cancelled too.
- **Lock a sibling `.lock` file**, never the file you work on. Closing any descriptor to a file drops the process's
  locks on it.
- The lock file and its missing folders are created and stay. Deleting one lets a new holder in beside the one still
  holding it.
- A new lock file is `644` outside Windows. The umask may narrow that but never widen it, so the group and everyone
  else never get to write it.
- A [private writer](writer.md#keep-a-secret)'s new lock files are `600`.
- A folder, FIFO, device or socket at the lock path is `NotRegularFile`.
- The lock is not reentrant. A second lock on a file this process holds waits as for any other holder. Then it is
  `LockBusy` naming this process.
- `withLock` blocks its thread while it waits. Coroutine code uses `awaitLock` from
  [`fs-coroutines`](../../fs-coroutines/README.md), which suspends instead.
- In a folder someone else can leave links in, lock through a [Writer](writer.md). `cache.withLock(".sync.lock")`,
  `cache.tryLock(name)` and `cache.awaitLock(name)` keep the lock file below its root.

## Tasks

### Run work of several steps under a lock

```kotlin
val merged: Result<String, FileError> = result {
    lockFile.awaitLock {
        val base = defaults.readText(maxBytes = 65_536).bind()
        val local = overrides.readText(maxBytes = 65_536).bind()
        base + local
    }.bind()
}
```

The first failed `bind()` releases the lock and ends the block with that error. The outer `bind()` passes on the
lock's own.

### Answer your app's own error

```kotlin
val text: Result<String, AppError> = result {
    lockFile.withLock { config.readText(maxBytes = 65_536).bind(AppError::File) }.bind(AppError::File)
}
```

`bind(AppError::File)` wraps a `FileError` in your app's case. Inside the lock it wraps the work's error. Outside it
wraps the lock's.

### Hold a lock for a whole run, and notice losing it

A server holds its lock for as long as it runs. Someone may delete the lock file meanwhile, so check on it now and
then.

```kotlin
while (true) {
    delay(30.seconds)
    lock.check().onError { failure -> if (failure is FileError.LockLost) return stopServing() }
}
```

`lockFile.tryLock()` tries once. It answers a `FileLock`, which is `AutoCloseable`, or `LockBusy` with a `waited` of
zero.

`check()` answers `LockLost(path)` once the lock file was deleted or replaced. That would let a new holder run beside
you.

### Keep a second instance of an app out

```kotlin
dirs.singleInstance("app").getOrElse { return report(it) }.use { runApp() }
```

`singleInstance(name)` takes `tryLock` on `.<name>.lock` in `dirs.state`. It writes this process's pid to
`<name>.pid`, so a `LockBusy` names the running holder as `holderPid`.

Closing the `InstanceLock` releases the lock. [A run's temp folder](run-temp.md#a-command-line-tools-startup) shows
it in a command-line tool's startup.

### Leave a pid marker for this session

```kotlin
dirs.runtime?.let { runtime ->
    val run = runtime.privateWriter().getOrElse { return report(it) }
    run.writeText("app.pid", "${currentProcessId()}\n").onError { report(it) }
}
```

`runtime` is null without `$XDG_RUNTIME_DIR`, and then no marker is written. Each start writes it again, so one a
killed run left is overwritten. Logout empties the folder.

If anything reads the marker as proof the app is running, remove it on a clean exit with `delete("app.pid")` on
the writer from the lambda above. Keep that writer past the lambda for it, as the daemon in
[Whole apps](apps.md#a-daemon) keeps its `marker`.

A killed run still leaves the marker behind until the next start.

An existing `runtime` others may read stops `privateWriter()` there. [Keep a secret](writing.md#keep-a-secret) fixes
it.

### Keep a second run of work on one path out

```kotlin
val instance = dirs.singleInstance(outputFolder).getOrElse { return report(it) }
instance.use { build(into = outputFolder) }
```

The key is resolved as `canonicalPath()` resolves it, so `out`, `src/../out/` and a link to `out` are one key, also
before `out` exists.

### Lock each of several things on its own

Give each key a sibling `.<key>.lock`. For example, `notes/a.md` is locked through `notes/.a.md.lock`. Those files
stay, one for every key ever locked.

## Reference

You do not need this part on a first read. Come back when a lock waits, fails or names a holder you did not expect.

**Mechanism and waiting:**

- It is `fcntl` on Linux and Apple, the primitive the JVM's `FileChannel.lock` also uses. On Windows it is
  `LockFileEx`.
- Between attempts it pauses 10 ms, then twice as long each time, at most 500 ms.
- The last pause is cut short so the pauses add up to `wait`. Their sum is the `waited` a `LockBusy` reports.
- `wait = Duration.INFINITE` waits until the holder lets go.
- Only an interrupt of a JVM thread ends a `withLock` wait early, as `LockBusy`. `awaitLock` also stops when its
  coroutine is cancelled.
- When you call `withLock` from a coroutine, the work may call suspending functions. The wait still blocks.
- `tryLock` answers a second lock of this process's own at once.
- On native Linux and Apple, a second spelling of one lock file can pass the in-process check, such as a dangling
  symlink or a hard link to it. `fcntl` then lets the same process through, so lock a plain file nothing links to.
- Without `result { }`, work that returns a `Result` comes back nested. `withLock { ... }.flatten()` makes it one,
  and `flatten(AppError::File)` maps the lock's error.
- Map after `flatten()`. Before it, `mapError` reaches only the lock's error.

**`check()`:**

- It follows a link at the path, as taking the lock does.
- A lookup that fails for another reason is that error, such as `Inaccessible`.
- Once the lock is closed, it answers `Ok`.
- The JVM on Windows has no file keys, so there it answers `Ok`. Windows native compares volume and index,
  unverified.
- It looks only when you call it, so a holder with no other events to check on, such as a server waiting for
  requests, checks on a timer.

**Who holds it:**

- `LockBusy.holderPid` is this process's own pid for a holder in this process.
- Another process is named on native Linux and Apple. It is null on the JVM, Android and Windows native, except from
  `singleInstance`.
- To name a holder there yourself, write your pid to a sibling file once you hold the lock, and read it on
  `LockBusy`. Never write it into the lock file.
- That pid can be stale: a killed holder leaves its pid behind.
- `currentProcessId()` is this process's pid on every platform, the one `holderPid` names.
- A `LockBusy` whose `holderPid` is `currentProcessId()` names this process. Either another thread or coroutine of
  this run holds the lock, or the code asking holds it already, since the lock is not reentrant.

**`singleInstance`:**

- The lock and pid file go in `dirs.state`, not `dirs.runtime`. `runtime` exists only with `$XDG_RUNTIME_DIR`, so a
  start without it would lock another file.
- Keep sockets and per-session files in `runtime`.
- It works through a private `Writer` on `dirs.state`. The pid and a `\n` are written by rename, readable by you
  alone.
- A link at either file's name is replaced, and what it led to is left alone.
- Unlike `privateWriter()`, it takes a `state` others may read. The lock file and the pid file are both `600`.
- A missing `state` is made `700`. Missing folders above it, such as `~/.local/state`, are made as a shared writer
  makes them: `755`, or narrower as the umask says. One already there keeps its permissions.
- A `state` another user owns is `Refused(path, info, why, state)` naming its real path. So is one its group or
  others may write, such as `777` or `775`. Then nothing is locked or written.
- Windows has no owner or mode bits, so any `state` passes there.
- `InstanceLock` has `lockFile` and `pidFile`. Closing it leaves both files, as every lock file stays.
- On `LockBusy`, `holderPid` is the pid the lock names. Failing that, it is the one the pid file holds, read with a
  bound.
- It is named only while a process with that pid is running. A pid file naming none is read again for up to 100 ms.
  Then `holderPid` is null.
- A pid file that holds no pid, or more than a pid takes, names no holder.
- The pid is written right after the lock is taken, so for a moment after a holder starts, or after one was killed,
  the file can name a stale pid.
- A running pid is still no proof. A pid a killed holder left can belong to an unrelated process by now.
- A `name` that `nameProblem()` refuses is `InvalidName` naming the lock file.
- So is a name over 249 bytes in UTF-8, as `TooLong`, since `.<name>.lock` would take more than 255.
- A pid file that cannot be written releases the lock again and is that write's error.
- A daemon stopped by `SIGTERM`, as `systemctl stop` sends, ends without its work returning unless it handles the
  signal. The system lets go of its lock.
- The daemon's [run temp](run-temp.md) stays until the next start's `clearStaleTempRuns()` clears it.
- To clean up on the signal, let `stopOnShutdownRequest()` from [`process`](../../process/README.md) end the work,
  as [this task](run-temp.md#clear-the-folder-on-ctrl-c) shows.
- Removing `dirs.state`, or the lock file, while the instance runs lets the next start run beside it.
  `instance.check()` answers `LockLost` then.
- Do not take a `singleInstance` lock file with `withLock`, `tryLock` or `awaitLock`. They write no pid, so where the
  lock cannot name its holder, a `LockBusy` names whatever running pid an earlier run left in the pid file.
- To wait for another instance, coroutine code calls `awaitSingleInstance` from
  [`fs-coroutines`](../../fs-coroutines/README.md#wait-for-another-instance). Its block is handed the
  `InstanceLock` it holds, so a long run can call `check()`.

**Keys:**

- Two folders are two keys.
- The instance name is the resolved path's last name and 16 lowercase hex digits. Those are the FNV-1a 64-bit hash of
  the resolved path's UTF-8 bytes. So `/srv/site/out` is `out-4c2894b7bcda536c`.
- A `/` or `\` in the last name becomes `_`.
- A last name too long for the lock file is cut to its first 232 bytes in UTF-8, never inside a character.
- Each key leaves its lock file and pid file in `dirs.state`.
- So `singleInstance(key)` keeps out only the runs that share that `dirs.state`. Another user, or a run with other
  `AppDirs`, takes the same key beside it.
