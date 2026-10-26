# A run's temp folder

A run often needs scratch space for files it builds and then copies or throws away. That space should be the run's
own, and it should be gone when the run ends.

`withRunTemp` gives this run a scratch folder of its own and clears it when the run ends. `clearStaleTempRuns` clears
what crashed runs left.

Part of [`fs`](../README.md).

```kotlin
inline fun <T> AppDirs.withRunTemp(block: (temp: Writer) -> T): Result<T, FileError>
fun AppDirs.staleTempRuns(): Result<StaleRuns, FileError>
fun AppDirs.clearStaleTempRuns(): Result<StaleCleanup, FileError>
```

## Rules

- `dirs.withRunTemp { temp -> }` claims `AppDirs.temp` from [`dirs`](../../dirs/README.md) and creates it `700`.
- It hands the block a private [Writer](writer.md) on that folder, so everything made there is yours alone and stays
  below it.
- When the block returns, throws or is cancelled, the folder is cleared and its lock file deleted. Both happen while
  the claim is still held.
- There is one run temp per `AppDirs`. A second `withRunTemp` on it while the first runs, in this process too, is
  `RunTempClaimed(path, holderPid)` at once, naming the folder by its real path, so claim it once and pass the
  writer down.
- A folder that cannot be cleared after a return makes that failure the result. After a throw, the throw wins.
- A killed process clears nothing. Ctrl-C and `SIGTERM` kill it unless the program handles them, so a daemon stopped
  by `SIGTERM`, as `systemctl stop` sends, leaves its run temp behind. The next start's `clearStaleTempRuns()` clears
  it.
- To clear it on the signal, let [`process`](../../process/README.md) end the block, as
  [Clear the folder on Ctrl-C](#clear-the-folder-on-ctrl-c) shows.
- `clearStaleTempRuns()` deletes the folders of this app's other runs that no live run holds, each with its lock. It
  answers what it `cleared` and what `failed`.
- Call it at startup, before `withRunTemp` or inside it.
- It is safe before `singleInstance` or after it. It claims each run as `withRunTemp` does, so a live run is passed
  over. So is a run another process is clearing at that moment, and no run is cleared twice.
- It tells live runs apart only when every run claims its folder with `withRunTemp`. A run that never did counts as
  stale.
- The run temp is often on another file system, such as a `tmpfs`, and a move across two is `WriteFailed`.
- Write a file meant for config, data or cache straight into that folder, or copy it there with `Writer.copyFrom`.

## Tasks

### Clear the folder on Ctrl-C

```kotlin
val stop = stopOnShutdownRequest().getOrElse { failure ->
    println("Ctrl-C will leave the run temp behind: $failure")
    null
}
dirs.withRunTemp { temp ->
    while (stop?.requested != true) processNextJob(temp)
}.onError { failure -> println("no run temp: $failure") }
```

`stopOnShutdownRequest()` from [`process`](../../process/README.md) turns Ctrl-C and `SIGTERM` into a flag the block
polls, so the block returns, and the folder is cleared.

With `onShutdownRequest { }` instead, the process exits once the handler returns, so the handler has to wait for the
block.

### A command-line tool's startup

```kotlin
/** Runs the tool once, answering its exit code. */
fun run(dirs: AppDirs): Int {
    val instance = dirs.singleInstance("app").getOrElse { failure ->
        val pid = (failure as? FileError.LockBusy)?.holderPid ?: "unknown"
        println("not started, already running as pid $pid: $failure")
        return 1
    }
    return instance.use {
        dirs.clearStaleTempRuns().fold(
            onSuccess = { cleanup -> cleanup.failed.forEach { println("old run ${it.folder} left: ${it.error}") } },
            onError = { failure -> println("old runs not cleared: $failure") },
        )
        dirs.withRunTemp { temp -> runApp(temp) }.getOrElse { failure ->
            println("no run temp: $failure")
            1
        }
    }
}
```

It holds [`singleInstance`](locking.md#keep-a-second-instance-of-an-app-out), clears what crashed runs left, and runs
in its own temp folder.

A pid file that holds no pid yet, or a stale one, prints as `unknown`.

### A command-line tool from its folders to its exit code

[Whole apps](apps.md#a-command-line-tool) has the whole program, from `main` through its config and data to its exit
code.

### Wait for another start instead of leaving

```kotlin
/** Runs [work] in this run's temp folder once no other start holds [name], waiting up to [wait] for one. */
suspend fun runWhenFree(
    dirs: AppDirs,
    name: String,
    wait: Duration,
    work: (Writer) -> Result<Int, FileError>,
): Result<Int, FileError> = result {
    dirs.awaitSingleInstance(name, wait) { dirs.withRunTemp { temp -> work(temp).bind() }.bind() }.bind()
}
```

`awaitSingleInstance` comes from [`fs-coroutines`](../../fs-coroutines/README.md#wait-for-another-instance). Each
`bind()` passes on one layer's error: the work's, the temp folder's, then the instance's.

Your work may answer your app's own error instead. Then `bind(AppError::File)` wraps the instance's and the temp
folder's errors in your case. It passes the work's error on as it is:

```kotlin
/** As [runWhenFree], for work that answers [AppError], whose case [AppError.File] wraps a [FileError]. */
suspend fun runAppWhenFree(
    dirs: AppDirs,
    name: String,
    wait: Duration,
    work: (Writer) -> Result<Int, AppError>,
): Result<Int, AppError> = result {
    dirs.awaitSingleInstance(name, wait) {
        dirs.withRunTemp { temp -> work(temp).bind() }.bind(AppError::File)
    }.bind(AppError::File)
}
```

### Hold a shared lock as well

Take the shared lock first, then claim the run temp inside it.

```kotlin
val thumbnails: Result<Thumbnails, FileError> = result {
    (dirs.cache / ".thumbnails.lock")
        .withLock { dirs.withRunTemp { temp -> buildThumbnails(temp) }.bind() }
        .bind()
}
```

## Reference

Below, "the folder holding the runs" is the parent of `dirs.temp`, which holds every run's temp folder.

### Claiming

- The claim is a lock on `.<run id>.lock` beside the folder, taken before the folder is created.
- A new claim file is `644`, as every new [lock file](locking.md#rules) is. It sits beside the `700` folder.
- The folder is `700` on Linux, Apple, Android and the JVM on a file system with POSIX permissions. One already there
  under a fixed run id is made `700` before the block runs.
- Windows leaves the folder's permissions to the folder it is made in.
- The folder holding the runs, and each missing one above it, is made as a shared writer makes a folder: `755`, or
  narrower as the umask says.
- Where the file system keeps POSIX permissions, the folder holding the runs must be yours. Its group and everyone
  else may not write it. Others may read it. Otherwise `withRunTemp`, `staleTempRuns()` and `clearStaleTempRuns()`
  all answer `Refused` naming it, and nothing is made, claimed or cleared in it.
- The block may call suspending functions when you call `withRunTemp` from a coroutine.
- A run temp already claimed is `RunTempClaimed`, not the `LockBusy` of a lock, and nothing is created.
- A second `withRunTemp` in this process names this process's pid as its `holderPid`. Another process holding the
  same fixed run id is named as `LockBusy` names a holder.

#### Sharp edges

- A symlink at the folder holding the runs is `NotADirectory` naming it by its folder's real path and its own name.
  That goes for `withRunTemp`, `staleTempRuns()` and `clearStaleTempRuns()` alike, with nothing made, claimed or
  cleared.
- Links above the folder holding the runs are followed, such as a `TMPDIR` that is a link.
- A symlink at the run temp's own path is not followed. Claiming is `NotADirectory`.
- A link put at the run temp's path while the block runs, one leading nowhere too, is `FolderReplaced` when the
  folder is cleared. What it leads to is left alone.
- On the JVM, Android, macOS and Windows, a link swapped in just as a call reaches the folder can be followed, as for
  any writer.

### Clearing stale runs

- Only folders named like a run id count. Anything else in the folder holding the runs, a symlink too, is never
  listed, deleted or followed.
- This run's own folder is never counted.
- Each folder is claimed as `withRunTemp` claims it, so a run still going is passed over.
- Every folder is tried. One it cannot clear goes into `failed` as a `StaleFailure(folder, error)`.
- That `error` names the entry that would not go. Or it is `LockFailed` naming the folder's `.<run id>.lock`, when
  that will not open.
- A folder another user owns or others may write fails as `Refused`. One others may only read is cleared.
- A folder that could not be cleared stays, so every later call fails on it again until it can be cleared.
- `staleTempRuns()` only lists. It answers a `StaleRuns` of the `stale` folders and the `failed` ones it could not
  check.
- `staleTempRuns()` leaves a `.<run id>.lock` beside each folder it looks at. `clearStaleTempRuns()` deletes it with
  the folder.
- Before any run has made the folder holding the runs, both answer `Ok` with nothing in either list.

#### Details

- `stale`, `cleared`, each `StaleFailure`'s `folder` and every error name real paths, with links above the folder
  holding the runs resolved.
- A folder swapped for a symlink after it was listed, one leading nowhere too, fails as `FolderReplaced`. The link
  and what it leads to are left alone.
- A folder gone by the time it is claimed is in neither list.
- A lock file with no folder beside it is left alone, since a run starting at that moment takes its claim before it
  creates its folder.

### When a claim fails

- A claim file that cannot be created, such as in a temp root this user may not write, is `WriteFailed` naming the
  folder holding the runs. Its `at` is the claim file, a `FailedAt.Claim`.
- That `WriteFailed` is the error of the whole call.
- A claim file that is there but will not open is `LockFailed` naming that file. It is the error of `withRunTemp`, and
  one of the `failed` of `staleTempRuns()` and `clearStaleTempRuns()`.
