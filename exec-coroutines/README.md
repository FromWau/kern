# exec-coroutines

[`kern:exec`](../exec/README.md) for coroutine code.

`events()` hands a child's events to you as a `Flow`, and `awaitExit()` suspends until it has exited. Neither blocks
the thread your coroutine runs on.

```kotlin
import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.coroutines.awaitExit
import com.fromwau.kern.exec.coroutines.events
import com.fromwau.kern.result.Result
import kotlinx.coroutines.flow.takeWhile

suspend fun printUntilReady(child: Child) {
    child.events()
        .takeWhile { event -> !(event is ChildEvent.Output && "ready" in event.text) }
        .collect { event -> if (event is ChildEvent.Output) print(event.text) }
}

suspend fun exitCode(child: Child): Int? = when (val exit: Result<Int, ExecError> = child.awaitExit()) {
    is Result.Success -> exit.value
    is Result.Error -> {
        println("no exit code: ${exit.error}")
        null
    }
}
```

`run()` starts a command, waits for it to exit, and gives you what it wrote and its exit code:

```kotlin
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.exec.coroutines.run
import com.fromwau.kern.result.getOrNull
import kotlin.time.Duration.Companion.seconds

suspend fun currentBranch(): String? {
    val git = Command(listOf("git", "branch", "--show-current"))
    return SystemStarter.run(git, timeout = 5.seconds).getOrNull()?.stdout?.trim()
}
```

**Targets:** the same as [`kern:exec`](../exec/README.md).

## Add to your build

```kotlin
dependencies {
    implementation("com.fromwau.kern:exec-coroutines:$kernVersion")
}
```

- It brings `kern:exec` and kotlinx-coroutines with it.
- Needs what [every kern module needs](../README.md#requirements), with JDK 22 or newer on the JVM.

## How it behaves

- `events()` gives what `Child.next()` gives, in that order, and ends when nothing is left to come. Collect it once:
  a second collector takes the events the first one has not.
- `awaitExit()` drops the output. It returns the exit code, the error of a `ChildEvent.Failed`, or
  `ExecError.Detached` when nothing is left to read without an exit: the child was detached, its events were read to
  the end already, or this is a second call.
- `awaitExit()` returns at `Exited` and leaves the child attached. A background job the child left running can still
  write, and that output is kept for `next()` until you take it. Call `detach()` after `awaitExit()` when you will not
  read the rest; until then the child keeps its zombie entry.
- Each child you collect or await holds one thread for as long as it is quiet. The threads come from a view of
  `Dispatchers.IO` that does not count against its limit, so many long-running children never hold up the rest of
  your IO.
- When a collection ends before the events ran out, by cancellation, an exception or `first` or `takeWhile`, the child
  is detached with `detach()`. Cancelling `awaitExit()`, or a throw from reading the child, does the same. Neither
  ever stops the child: it keeps running, and `stop()` is the only thing that ends one. `stop()` still reaches a
  detached child, so a wait that timed out can end in a kill:

  ```kotlin
  withTimeoutOrNull(5.seconds) { child.awaitExit() } ?: child.stop(Signal.Kill)
  ```
- On the JVM, grant native access as [Platforms](../exec/README.md#platforms) says, or every start fails.
- The thread waiting for the child is released at once when you cancel.
- `events()` and `awaitExit()` take an optional `dispatcher` for the blocking reads, the unlimited view of
  `Dispatchers.IO` by default.
- A child that is a `SuspendingChild`, such as `FakeChild` from [`exec-test`](../exec-test/README.md), is awaited
  instead: its `awaitNext()` suspends on the caller's context and the dispatcher goes unused, so `runTest` drives it
  in virtual time.

`run()`:

- Any exit code is `Ok(Finished(stdout, stderr, exitCode))`: `grep` finding nothing exits 1, which is an answer.
- Nothing in the command's process group outlives the call. When the command exits, a background job it left running
  is stopped, and what it writes after the exit is not kept.
- A job meant to outlive the call needs a session of its own and its output elsewhere: `setsid job >/dev/null 2>&1 &`
  in a script, or `start()` with `Command(newSession = true)` and `Output.Discard`.
- Stopping sends SIGTERM to the group and SIGKILL two seconds later, so a call can take up to two seconds longer
  than the command.
- Past `timeout` the group is stopped the same way and the call answers `ExecError.TimedOut(after, stdout, stderr)`
  with what both streams had got. Cancelling the call, a `ChildEvent.Failed`, or a throw from the child's `next()`
  stops the group too, without the call waiting for it. The throw reaches you.
- Everything the command writes is kept in memory. Read a command whose output has no end with `events()`.
- It takes a `Starter`, so a test hands it a `FakeStarter` from [`exec-test`](../exec-test/README.md); a live fake
  child's timeout then runs in virtual time.

## License

[Apache-2.0](../LICENSE).
