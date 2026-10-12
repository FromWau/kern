# logger-async

Writes [`kern:logger`](../logger/README.md) entries on a background writer, so the thread that logs never waits
for the disk or a slow sink. It is for a Compose app's main thread, or any thread where a few microseconds per
log line matter.

```kotlin
import com.fromwau.kern.logger.async.AsyncDelivery

val logger = Logger(delivery = AsyncDelivery())
logger.configure(LoggerConfig(file = FileOutput.Append(Path(stateDir, "app.log"))))

logger.tag("Scanner").i { "scan complete" }   // returns before the line is written
logger.close()                                 // waits for the queue to drain, then closes the file
```

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64. Not JS or Wasm, where
`close()` could not wait for a writer on the one thread there is.

## Add to your build

Releases are published as `com.fromwau.kern:logger-async` to
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/logger-async), which needs no
credentials to read. It brings `kern:logger` with it.

```kotlin
dependencies {
    implementation("com.fromwau.kern:logger-async:$kernVersion")
}
```

The JVM and Android artifacts are Java 25 bytecode, so those two need a JDK 25 toolchain.

## What changes

The level is still checked, and the message built, on the thread that logged, so a filtered entry costs the same
as before. Only the writing to the file, the sinks and the console moves to the writer, one entry at a time and
in the order they were logged. Each entry is written with the `LoggerConfig` in force when the writer gets to it.

Entries wait in a queue of `capacity` (10,000). When the writer falls that far behind, `drop` decides which entry
gives way, `DropPolicy.OLDEST` by default, and a log call never blocks:

```kotlin
AsyncDelivery(capacity = 50_000, drop = DropPolicy.NEWEST)
```

`delivery.health` is an `AsyncHealth`: `dropped` counts what gave way, and `failed` and `lastError` what a
`FileFailure.Throw` or `SinkFailure.Throw` failed with, since that failure can no longer reach the call that
logged; the logger also reports the first such failure on standard error. Test with the default inline delivery
when a broken sink should fail the test. Every other failure is handled by the logger
as usual and shows in `logger.health`. An `Error` such as `StackOverflowError` thrown while writing stops the writer for
good, as it would have stopped your thread: entries after it are dropped and counted, and `close()` still
returns.

One `AsyncDelivery` may serve several loggers. Each entry is still written under its own logger's lock, and
the queue, its capacity and its `health` are shared.

## Before the process ends

`delivery.flush()` waits until every entry logged so far is written, without closing anything. Call it where
the platform may end the process without warning: in `onStop` on Android, or on
`applicationDidEnterBackground` on iOS. `logger.close()` waits for every entry already logged, so call it
before you exit, from an ordinary thread and never from inside a sink. On the JVM the queue is also drained
when the process exits, including after an uncaught exception, and on Android before an uncaught exception
ends the app; both wait at most `flushOnExit` (two seconds). Native targets have no such hook, so there an
entry still queued when the process ends is lost.

## When it is worth it

Measured with `./gradlew :logger-benchmark:benchmark` on an Intel Core i9-12950HX, Linux 7.2, OpenJDK 25.0.4,
the log file on btrfs. Each line is one `log.i(...)` with three fields, as the thread that logs pays for it:

| writing                     | one thread | four threads at once |
|-----------------------------|-----------:|---------------------:|
| on the calling thread, text | 6.4 µs     | 45 µs                |
| on the calling thread, JSON | 6.6 µs     | 45 µs                |
| `AsyncDelivery`, text       | 0.87 µs    | 7.2 µs               |
| `AsyncDelivery`, JSON       | 0.85 µs    | 7.7 µs               |

A filtered entry costs 4 ns either way. Writing on the calling thread costs a few microseconds per line, nearly
all of it the file write, and several threads logging at once queue for it. If your app logs a handful of lines
per second, that is nothing and you do not need this module. Reach for it when a thread that must stay smooth
logs often, or when several threads log heavily at the same time. At these rates the benchmark outruns the
writer, so the async numbers are the cost of handing over, with the queue dropping what the disk cannot keep up
with.

## Under heavy load

`./gradlew :logger-benchmark:stress` logs a million JSON entries from 1 to 256 threads into one file, then
checks every line. On the same machine:

| threads | on the calling thread: entries/s, p99 | `AsyncDelivery()`: p99, dropped | no drops (`capacity` = all): p99, `close()` |
|--------:|---------------------------------------|---------------------------------|---------------------------------------------|
| 1       | 144,000/s, 18 µs                      | 3.6 µs, 92%                     | 1.5 µs, 8.6 s                               |
| 16      | 78,000/s, 2.3 ms                      | 47 µs, 96%                      | 9.6 µs, 24 s                                |
| 256     | 46,000/s, 54 ms                       | 50 µs, 96%                      | 14 µs, 9.9 s                                |

In every run each line was whole, each thread's entries were in the order it logged them, and every entry was
either in the file or counted as dropped. The file takes about 50,000 to 150,000 entries a second however they
arrive, so a background writer does not make logging faster overall: it moves the wait off the threads that log.
Logged faster than that for long, it either drops (the default) or, with a queue large enough to hold
everything, makes `close()` wait while it catches up.

## License

[Apache-2.0](../LICENSE).
