# process

Shutdown requests for Kotlin Multiplatform: one handler for Ctrl-C, `SIGTERM` and `SIGHUP`, run on an ordinary
thread, after which the process exits with the code a shell expects.

A signal handler is a hard place to run code: almost nothing is safe to call in one, and each platform delivers
signals differently. This module takes the request out of the signal handler and gives it to your code on a
thread of its own (on Node, on its event loop), the same way on every target that has signals.

**Targets:** JVM, Android, js, wasmJs, wasmWasi, linuxX64, linuxArm64, mingwX64, androidNativeArm64,
androidNativeX64, androidNativeArm32, androidNativeX86. macOS and iOS are not built yet: their C part needs a Mac
to compile.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:process:$kernVersion")
}
```

KMP consumers put it in `commonMain`. Everything is in the package `com.fromwau.kern.process`. Needs what
[every kern module needs](../README.md#requirements).

## Stopping a server

```kotlin
import com.fromwau.kern.process.onShutdownRequest
import com.fromwau.kern.result.onError

fun main() {
    val server = startServer()
    onShutdownRequest { server.stop() }
        .onError { println("shutdown requests are not handled: $it") }
    server.awaitTermination()
}
```

The handler is a plain `(ShutdownRequest) -> Unit`, not a `suspend` function. It runs once, for the first request,
on a thread of its own (on Node, on its event loop), and gets which request it was. When it returns, the process
exits with the `exitCode` you passed, or without one with that request's `ShutdownRequest.exitCode`:

| request | `ShutdownRequest` | `exitCode` |
|---|---|---|
| Ctrl-C (`SIGINT`) | `Interrupt` | 130 |
| `kill` (`SIGTERM`) | `Terminate` | 143 |
| the terminal closes (`SIGHUP`) | `Hangup` | 129 |

These are the shell's 128+N codes for a process ended by signal N, so a script sees the same code as when no
handler was installed. The exit is the platform's normal one: shutdown hooks run on the JVM, and `atexit`
handlers natively.

**Your own exit code:** pass it as `exitCode`, as in `onShutdownRequest(exitCode = 0) { server.stop() }`. The
process exits with it once the handler returns, and shutdown hooks and `atexit` handlers still run. It must be in
0..255: anything else throws `IllegalArgumentException` and installs nothing. Validate a code read from
configuration before starting other threads, since that exception ends only the thread that calls
`onShutdownRequest`. A systemd service counts exit 143 as a failure, so either pass `exitCode = 0` or set
`SuccessExitStatus=143` in its unit (add 129 and 130 as needed).

**A handler that throws** does not stop the exit. Its stack trace goes to standard error, as an uncaught exception's
would, and the process exits with the request's code: `exitCode` is used only when the handler returns.

## Stopping a worker loop

`stopOnShutdownRequest` installs no handler: it records the first request, and your loop polls it and stops on its
own:

```kotlin
import com.fromwau.kern.process.stopOnShutdownRequest
import com.fromwau.kern.result.getOrNull
import com.fromwau.kern.result.onError

fun main() {
    val stop = stopOnShutdownRequest()
        .onError { println("shutdown requests are not handled: $it") }
        .getOrNull()
    try {
        while (stop?.requested != true) processNextJob()
    } finally {
        deleteTempFiles()
    }
}
```

After a request, the process ends when `main` returns or anything exits, with the `exitCode` you passed, or without
one with the request's code; before a request it ends as usual. Any exit counts, and on the JVM, Linux and
androidNative a crash too: with `exitCode = 0` a cleanup that crashes exits 0, so leave `exitCode` null if a crash
must not look like success. Windows native does not handle a crash yet.
`stop.request` says which request it was. It shares `onShutdownRequest`'s one install per process, its errors and its
0..255 rule. A second request ends the process at once, as for a handler.

- **JVM:** your shutdown hooks finish first, except that one marked daemon may be cut off unless kern itself starts
  the exit; then the process ends with the stop's code. In a program started by the
  `java` launcher, once `main` has returned a request ends the process even while another non-daemon thread runs,
  as it would without kern; a custom launcher may not allow kern to see that `main` returned. When main returns
  after a stop, app shutdown hooks finish and other threads are cut off, as under a plain SIGTERM: join the workers
  that must finish before main returns. When a thread calls `exitProcess` after a stop, kern also waits for
  non-daemon threads started after the request, so one that never ends keeps the process alive until a second
  request. A shutdown hook that never returns keeps the process alive
  too, as it would in a plain JVM shutdown. Do not call `exitProcess` from a shutdown hook: the JVM blocks there,
  and only a second request ends it. The exit is a `Runtime.halt`, so files marked with `File.deleteOnExit` are not
  deleted.
- **Linux, androidNative:** `atexit` handlers you registered after the call run first; an exception escaping `main`
  goes through kern's unhandled-exception hook, as with a handler.
- **Node:** requests arrive on the event loop, so a loop that never yields never sees one. Poll from asynchronous
  code, such as a coroutine that suspends between jobs.

**On the JVM, with a handler instead**, the handler can ask main's loop to stop and wait until the loop has finished
its last job:

```kotlin
import com.fromwau.kern.process.onShutdownRequest
import com.fromwau.kern.result.onError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

fun main() {
    val stopping = AtomicBoolean(false)
    val stopped = CountDownLatch(1)
    onShutdownRequest {
        stopping.set(true)
        stopped.await()
    }.onError { println("shutdown requests are not handled: $it") }
    try {
        while (!stopping.get()) processNextJob()
    } finally {
        stopped.countDown()
    }
}
```

The process exits once the handler returns, after the loop's last job. A job that blocks, such as `queue.take()`,
never sees `stopping`: poll with a timeout instead, or interrupt the worker from the handler. Leave the loop by
returning or throwing, never by calling `exitProcess` inside it: that skips the `finally`, and the handler then waits
for `stopped` forever.

## While a request is handled

**A second request ends the process at once**, with its own code, whether your handler is still running or the
process is already exiting. So `SIGTERM` and then `SIGHUP` exits 129, and a user whose first Ctrl-C hangs can
press it again. Nothing else runs then: no rest of your handler, no `finally` blocks, no shutdown hooks and no
`atexit` handlers; under Node, only the process's `'exit'` listeners run. So in your cleanup, delete partial files
first.

**Bound a slow handler yourself.** kern has no timeout of its own, and Docker and systemd do not send a second
request when the handler takes too long: after their own stop timeout they send `SIGKILL`, which ends the process
without warning. Give your drain a deadline shorter than theirs.

**Do not call `exitProcess` in the handler.** Natively, when `main` has returned or is exiting, that call blocks
forever: the C library's exit already under way holds the lock it needs. On the JVM, an `exitProcess` in the handler
while `main` is exiting at the same time leaves `main`'s code.

**Main may return, throw or exit while the handler runs.** The process then still waits for the handler and exits
with its code, whether `main` returned, threw, or a thread called `exitProcess`, on the JVM, Linux and
androidNative. An exception that escapes `main` prints its stack trace first. Natively, `atexit` handlers you
registered before calling `onShutdownRequest` do not run in that case; those registered after it run before the
wait. On the JVM such an exit starts your shutdown hooks at once, alongside the handler, as JVM hooks always run
concurrently, and a hook still running when the handler finishes is cut off. Windows native does not wait yet.

**On Linux and androidNative, an exception escaping `main`** goes through an unhandled-exception hook kern installs;
Windows native does not handle this yet. To report crashes yourself, set your own hook before calling
`onShutdownRequest`:

```kotlin
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.setUnhandledExceptionHook

@OptIn(ExperimentalNativeApi::class)
fun reportCrashes() {
    setUnhandledExceptionHook { e -> println("crashed: $e") }
}
```

Kern passes every uncaught exception on to it, also during shutdown: your hook runs, then a running handler finishes
and the process exits with the handler's code, or with a stop's code. Without a hook of yours, kern prints the stack
trace instead. A hook set after `onShutdownRequest` replaces kern's, and an exception escaping `main` then ends the
process at once.

**A main thread reading standard input**, as `readlnOrNull()` does, keeps a native program from exiting after your
handler returns until that read returns, because the C library's exit waits for that stream. A second request ends
it at once.

## Requests that never reach your code

**A request ignored at startup stays ignored.** `nohup app` starts it with `SIGHUP` ignored, and a shell script's
`app &` starts it with `SIGINT` ignored. Those stay ignored, so the program keeps running when the terminal closes;
the other requests are still handled. Under Node a program cannot see this, so there all three are handled. Windows
has no such check yet. A test harness that starts your program with `&` from a non-interactive shell therefore cannot
reach it with Ctrl-C's `SIGINT`; send `SIGTERM` instead. Running the program under `script` or another pty from such a
backgrounded job does not reset it.

`SIGKILL` and `SIGSTOP` cannot be caught on any platform, so `kill -9` ends the process without running anything.
`SIGPIPE` and `SIGQUIT` are not shutdown requests either. Natively, a write to a closed pipe ends the process with
141 and runs no handler; to act on the closed pipe instead, ignore `SIGPIPE` with `signal(SIGPIPE, SIG_IGN)` at
startup, and the write then fails with an error. The JVM ignores `SIGPIPE` itself, and `System.out` swallows the
write error: check `System.out.checkError()`. See
[kern:terminal's README](../terminal/README.md#writing-your-last-message-after-a-closed-pipe) for a snippet.

## In a library

Leave the call to the application: a process has one handler, and the application picks the exit code. Expose a
stop function, or take a `ShutdownSource`, whose members are

```kotlin
fun onShutdownRequest(exitCode: Int? = null, handler: (ShutdownRequest) -> Unit): Result<Unit, ShutdownError>
fun stopOnShutdownRequest(exitCode: Int? = null): Result<ShutdownStop, ShutdownError>
```

and call them without an `exitCode`. The application passes `systemShutdownSource()`, or wraps it to choose a code:

```kotlin
class WithExitCode(private val source: ShutdownSource, private val code: Int) : ShutdownSource {
    override fun onShutdownRequest(
        exitCode: Int?,
        handler: (ShutdownRequest) -> Unit,
    ): Result<Unit, ShutdownError> = source.onShutdownRequest(exitCode ?: code, handler)

    override fun stopOnShutdownRequest(exitCode: Int?): Result<ShutdownStop, ShutdownError> =
        source.stopOnShutdownRequest(exitCode ?: code)
}

val server = LibraryServer(WithExitCode(systemShutdownSource(), code = 0))
```

A wrapper with a code outside 0..255 makes the library's install call throw `IllegalArgumentException`.

## Errors

`onShutdownRequest` returns a `Result<Unit, ShutdownError>`, and `stopOnShutdownRequest` a
`Result<ShutdownStop, ShutdownError>`. Installing is all or nothing: on an error nothing stays installed.

| `ShutdownError` | when |
|---|---|
| `AlreadyInstalled` | a handler or a stop is already installed in this process. A call that failed does not count |
| `Unsupported` | no requests reach this platform: a browser, WASI, or an Android app |
| `Reserved(requests)` | the JVM keeps the requests in `requests` for itself, as it does for all three with `-Xrs` |
| `OutOfResources` | the system ran out of file descriptors, threads or memory |
| `System(code)` | the system refused for another reason; `code` is its `errno`, or `GetLastError` on Windows |

```kotlin
import com.fromwau.kern.process.ShutdownError
import com.fromwau.kern.result.Result

when (val installed = onShutdownRequest { server.stop() }) {
    is Result.Success -> Unit
    is Result.Error -> when (val error = installed.error) {
        ShutdownError.AlreadyInstalled -> println("another part of the program already handles shutdown requests")
        ShutdownError.Unsupported -> println("this platform sends no shutdown requests")
        is ShutdownError.Reserved -> println("the JVM keeps ${error.requests} for itself")
        ShutdownError.OutOfResources -> println("out of resources")
        is ShutdownError.System -> println("the system refused: ${error.code}")
    }
}
```

Import kern's `Result`: without it, `Result` is Kotlin's own and the `when` does not compile.

## Platforms

| platform | requests | how |
|---|---|---|
| Linux, androidNative | `SIGINT`, `SIGTERM`, `SIGHUP` | a C signal handler wakes a thread of the module's own, which runs your handler |
| JVM on Linux and macOS | `SIGINT`, `SIGTERM`, `SIGHUP` | `sun.misc.Signal`; your handler runs on a thread of its own |
| JVM on Windows | `SIGINT`, `SIGTERM` | the JVM there has no `SIGHUP` |
| Windows native (mingwX64) | Ctrl-C as `Interrupt`, closing the console as `Hangup` | `SetConsoleCtrlHandler`; Ctrl-Break is left alone |
| Node (js, wasmJs) | `SIGINT`, `SIGTERM`, `SIGHUP` | `process.on` |
| browser, WASI, Android app | none | `Unsupported` |

**Closing a Windows console** gives your handler, or with a stop `main`, about 5 seconds: Windows ends the process
then, however far it got.

**Node runs your handler on its event loop**, the program's only thread. A handler that blocks without returning
keeps a second request from ever arriving. The process exits as soon as the handler returns, so asynchronous work
it starts does not finish. The exit is `process.exit`, also for a second request, so the process's `'exit'`
listeners still run.

**An Android app** is ended by the system with `SIGKILL`, which no handler sees, so there the answer is
`Unsupported`. Use the activity and service lifecycle instead.

## Testing

Code that takes a `ShutdownSource` can be tested with `FakeShutdownSource` from `kern:process-test`, which sends a
request to your handler on the test's thread without ending the process. See the
[process-test README](../process-test/README.md).

## License

[Apache-2.0](../LICENSE).
