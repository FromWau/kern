# process-test

A test `ShutdownSource` for [`kern:process`](../process/README.md). `FakeShutdownSource` runs your shutdown
handler when the test sends a request, on the test's own thread, and records the exit code the process would have
ended with. Nothing exits.

**Targets:** the same as [`kern:process`](../process/README.md).

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:process-test:$kernVersion")
}
```

KMP consumers put it in `commonTest`. `kern:process` comes along as `api`. `FakeShutdownSource` is in the package
`com.fromwau.kern.process.test`. Needs what [every kern module needs](../README.md#requirements).

## Sending a request

Write the code under test against `ShutdownSource`, pass it `systemShutdownSource()` in the program and a
`FakeShutdownSource` in the test:

```kotlin
import com.fromwau.kern.process.*
import com.fromwau.kern.process.test.FakeShutdownSource
import com.fromwau.kern.result.onError
import kotlin.test.*

class Server(source: ShutdownSource) {
    var stopped = false
        private set

    init {
        source.onShutdownRequest { stopped = true }
            .onError { println("shutdown requests are not handled: $it") }
    }
}

@Test
fun `a terminate request stops the server`() {
    val source = FakeShutdownSource()
    val server = Server(source)

    source.request(ShutdownRequest.Terminate)

    assertTrue(server.stopped)
    assertEquals(143, source.exitCode)
}
```

| member | what it does |
|---|---|
| `installed` | whether a handler or a stop is installed |
| `stopOnShutdownRequest(exitCode)` | returns a `ShutdownStop` that the first request sets |
| `request(request)` | the first request runs the handler or sets the stop; a later one only records its code, as the process would end at once |
| `exitCode` | null until a request arrives, then the `exitCode` given at install or the request's own; the request's own when the handler throws; a later request replaces it with its own code |
| `thrown` | what the handler threw, kept instead of printed |
| `failWith(error)` | the next `onShutdownRequest` or `stopOnShutdownRequest` that is not `AlreadyInstalled` returns `error` and installs nothing; the call after it installs as usual |

A second install, of either kind, after a successful one answers `ShutdownError.AlreadyInstalled`, and an `exitCode`
outside 0..255 throws `IllegalArgumentException`, as the real one does.

**Install before sending requests.** A request with no handler installed counts as the process ending: it records its
code and runs nothing, so a handler installed after it never runs.

**The handler runs on the thread calling `request`**, before `request` returns. A handler that waits for something
that same thread must do, such as a worker loop the test drives, deadlocks: send the request from another thread
instead. With `stopOnShutdownRequest` there is nothing to deadlock, since `request` only sets the stop.

**The fake cannot end a handler early.** A second request sent while the handler runs, from the handler itself or
another thread, records its code, but the rest of the handler still runs. Only `exitCode` shows that a real process
would have ended there.

A handler that calls `exitProcess` still ends the process, which in a test is the test run itself.

## License

[Apache-2.0](../LICENSE).
