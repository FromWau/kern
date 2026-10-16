# logger-test

Test sinks for [`kern:logger`](../logger/README.md). `TestSink` keeps every entry your code logs and waits for the
next one. `RecordingSink` wraps a sink of your own, so you can test it through a real `Logger` and see each write
with what it returned.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64, js, wasmJs, the same as
`kern:logger`.

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:logger-test:$kernVersion")
    testImplementation("com.fromwau.kern:result-test:$kernVersion")  // assertSuccess(), as the snippets here use
}
```

KMP consumers put them in `commonTest`. `kern:logger`, `kotlinx-coroutines-core` and `kotlin-test` come along as
`api`; `kern:result-test` does not, so add it to use `assertSuccess()`. Everything is in the package
`com.fromwau.kern.logger.test`. Needs what [every kern module needs](../README.md#requirements).

## Checking what your code logs

Add a `TestSink` to a `Logger` of the test's own, hand that logger to the code under test, and take the entries
in the order they were logged:

```kotlin
import com.fromwau.kern.logger.*
import com.fromwau.kern.logger.test.TestSink
import com.fromwau.kern.result.assertSuccess
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class CheckoutTest {
    private val sink = TestSink()
    private val logger = Logger()

    @BeforeTest
    fun setUp() {
        logger.configure { LoggerConfig(listOf(ConfiguredSink(sink, SinkConfig(LogLevel.DEBUG)))) }.assertSuccess()
    }

    @AfterTest
    fun tearDown() {
        logger.close().assertSuccess()
    }

    @Test
    fun `checkout logs each step in order`() = runTest {
        Checkout(logger.tag("Checkout")).run(cart)

        val steps = List(3) { sink.awaitEntry().message }

        assertEquals(listOf("cart checked", "payment taken", "order placed"), steps)
    }
}
```

`assertSuccess()` comes from [`kern:result-test`](../result/README.md#testing).

A `TestSink` takes every sink's defaults: a `capacity` of 10,000, `DropPolicy.OLDEST` and `SinkFailure.Drop()`. Its
level is the one its `ConfiguredSink` gives it, and `ConfiguredSink(sink)` alone takes `INFO` and above, so pass
`SinkConfig(LogLevel.VERBOSE)` to see every entry.

| member               | what it gives you                                                                       |
|----------------------|-----------------------------------------------------------------------------------------|
| `awaitEntry(timeout)`| the next entry not yet taken, waiting up to `timeout`, one second by default            |
| `entries`            | a `Flow` of the entries not yet taken, each once, in the order they arrived             |
| `received`           | a snapshot of every entry so far, whether or not it was taken                           |

`awaitEntry()` and `entries` share one queue, so each entry reaches only one of them. `awaitEntry()` waits in real
time, even under `runTest`'s virtual clock, and fails the test when nothing arrives in time, naming the sink and
the timeout:

```
TestSink "test" got no entry within 1s
```

`entries` never ends by itself, so take what you expect from it:

```kotlin
import kotlinx.coroutines.flow.*

val levels = sink.entries.take(2).map { it.level }.toList()
```

Every write to a `TestSink` succeeds. Its name is `"test"` unless you pass another, which you need for two of them
in one logger.

Under a flood a `TestSink` drops like any sink: once 10,000 entries wait for it, each new one pushes out the
oldest. Its name is its only parameter, so its capacity cannot be raised. The drop shows when the logger closes:
`close()` returns a `CloseError` with `Lost(count)` under the sink's name.

```kotlin
logger.close().onError { failed -> fail("dropped under a flood: ${failed.errors["test"]}") }
```

To keep every entry, wait with `logger.awaitFlushed()` before each 10,000 entries, or record through a
`RecordingSink` around a sink with the capacity you need (see
[Testing a sink of your own](#testing-a-sink-of-your-own)):

```kotlin
val sink = RecordingSink(LogSink("flood", capacity = 100_000) { _, _ -> Ok(Unit) })
```

### Tests on threads

`awaitEntry()` and `awaitCall()` suspend. A test that runs on threads rather than in `runTest` waits in
`runBlocking { }`, on the JVM and native:

```kotlin
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

@Test
fun `the worker logs from its own thread`() {
    thread { logger.tag("Worker").i { "started" } }.join()

    val entry = runBlocking { sink.awaitEntry() }

    assertEquals("started", entry.message)
}
```

## Testing a sink of your own

Wrap your sink in a `RecordingSink` and add that to the logger instead. Your sink does the work, and every entry
it is handed is recorded as a `SinkCall` with the `entry`, the `config` and the `result` its write returned. The
wrapper takes its name, `onFailure`, `capacity` and `drop` from your sink, and its `close()` closes yours, so the
logger treats it as it treats your sink. A batching sink such as `FileSink` still gets queued entries several at a
time, and each entry of such a write is recorded as a `SinkCall` of its own with the write's result.

This checks that an upload the server refuses once is tried again under `SinkFailure.Retry`. The `UploadSink` here
takes a fake server and its `onFailure` as parameters, unlike the one in the kern:logger README:

```kotlin
import com.fromwau.kern.logger.test.RecordingSink
import com.fromwau.kern.result.*
import kotlin.time.Duration.Companion.milliseconds

@Test
fun `a refused upload is tried again`() = runTest {
    val server = FakeServer(refusals = 1)
    val sink = RecordingSink(UploadSink(server, onFailure = SinkFailure.Retry(backoff = 10.milliseconds)))
    logger.configure { LoggerConfig(listOf(ConfiguredSink(sink))) }.assertSuccess()

    logger.tag("app").i { "hello" }

    val refused = sink.awaitCall()
    val retried = sink.awaitCall()
    assertEquals(Err(SinkError.Dropped(UploadError.Refused)), refused.result)
    assertEquals(Ok(Unit), retried.result)
    assertEquals("hello", retried.entry.message)
}
```

Each attempt is its own call, so a retried entry shows up once for every try. `awaitCall()`, `calls` and
`received` work as `awaitEntry()`, `entries` and `received` do on a `TestSink`.

A write that throws is recorded as `Err(SinkError.Dropped(Thrown(e)))`, which is how the logger reports a throw,
and the exception goes on to the logger. Your sink's failure shows in the logger's health exactly as it
would without the wrapper.

### Timings to wait for

The logger has a few fixed timings, which a test cannot shorten:

- A sink gets at most one warning a second for entries it dropped. The first comes with the next write after a
  drop. One held back by that limit is written with the first entry the sink takes after the second has passed,
  or by a close. Nothing writes it on its own, so log one more entry, or close, before you await it.
- A sink removed with `configure` while it keeps failing under `SinkFailure.Retry` gives up after 5 seconds, and
  drops what it still has.
- `SinkFailure.Retry` waits its `backoff` between tries, one second by default. `Retry(backoff = Duration.ZERO)`
  tries again at once, without pause, so against a fake that keeps failing it calls the fake thousands of times
  a second. Use it only with a fake that recovers, such as `FakeServer(refusals = 1)` above.

Wait for what comes after a timing with `awaitCall()` or `awaitEntry()` and a timeout above it. This waits for the
warning that follows a dropped upload, the write after the drop. It loses an entry on purpose, so it uses a logger
of its own rather than one whose `tearDown` expects a clean close:

```kotlin
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Test
fun `a dropped upload is announced`() = runTest {
    val logger = Logger()
    val server = FakeServer(refusals = 1)
    val sink = RecordingSink(UploadSink(server, onFailure = SinkFailure.Drop(backoff = Duration.ZERO)))
    logger.configure { LoggerConfig(listOf(ConfiguredSink(sink))) }.assertSuccess()

    logger.tag("app").i { "refused" }
    logger.tag("app").i { "accepted" }

    val (refused, warning, accepted) = List(3) { sink.awaitCall(timeout = 3.seconds) }
    assertEquals("refused", refused.entry.message)
    assertEquals("kern.logger", warning.entry.tag)
    assertEquals(1L, warning.entry.fields["dropped"])
    assertEquals("accepted", accepted.entry.message)
    assertEquals(Err(CloseError(mapOf("upload" to listOf(SinkError.Lost(1))))), logger.close())
}
```

## License

[Apache-2.0](../LICENSE).
