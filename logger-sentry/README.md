# logger-sentry

A Sentry sink for [`kern:logger`](../logger/README.md): `SentrySink` sends each entry to Sentry as an event, a
breadcrumb and a log, each from a level of its own, through the
[Sentry Kotlin Multiplatform SDK](https://docs.sentry.io/platforms/kotlin-multiplatform/) your app initialises.

```kotlin
import com.fromwau.kern.logger.*
import com.fromwau.kern.logger.sentry.SentrySink
import com.fromwau.kern.result.*
import io.sentry.kotlin.multiplatform.Sentry

Sentry.init { options ->
    options.dsn = "https://<key>@<org>.ingest.sentry.io/<project>"
    options.logs.enabled = true
}
Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(SentrySink())) }
    .onError { error -> Log.tag("App").w(error) { "not logging to Sentry" } }

Log.tag("Checkout").e(failure, "order" to orderId) { "payment failed" }
```

`onError` comes from [`kern:result`](../result/README.md#consuming-one).

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64, js, wasmJs. Sentry only
sends from Android, the JVM and Apple; see [where it sends](#where-it-sends).

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:logger-sentry:$kernVersion")
    implementation("io.sentry:sentry-kotlin-multiplatform:0.27.0")
}
```

kern is built and tested against the Sentry version shown. KMP consumers put both in `commonMain`. `kern:logger`
comes along as `api`. The Sentry SDK comes along at runtime only, so declare it yourself to call `Sentry.init`;
Gradle uses the newer of your version and kern's. On Apple your app also links the Sentry Cocoa SDK, as Sentry's
setup guide describes. Needs what [every kern module needs](../README.md#requirements).

## Where it sends

Sentry's SDK sends from Android, the JVM and Apple; `SentrySink.sendsHere` says whether it does on this target. On
JS, Wasm, Linux and Windows it does nothing.

Until `Sentry.init` has run, after `Sentry.close()`, and on a target where Sentry does nothing, every write of an
entry that reaches a destination fails with `SinkError.Dropped` carrying `SentryError.NotEnabled`. Those entries
are dropped and counted in `health.dropped`, so a flush or close reports them as lost. Add the sink only while
Sentry runs, as [consent](#consent) shows, and nothing fails.

If entries were dropped, the sink's first write after Sentry starts is preceded by the logger's own `WARN` entry
`dropped N entries this sink did not take, logged ...`, which by default goes out as a breadcrumb and a log.

## The three destinations

`SentrySink(name, events, breadcrumbs, logs, tags, user, onFailure, capacity, drop)` sends an entry to every
destination whose level it reaches. Nothing is required: `name` is `sentry`, `tags` is empty, `user` is null,
`capacity` is 10,000 and `drop` is `DropPolicy.OLDEST`. `onFailure` is `SinkFailure.Drop(backoff = Duration.ZERO)`,
so the first entry after `Sentry.init` goes out.

| destination   | default | an entry becomes                                                       |
|---------------|---------|------------------------------------------------------------------------|
| `events`      | `ERROR` | an issue: its throwable as the exception, or without one its message   |
| `breadcrumbs` | `INFO`  | a breadcrumb, part of the trail Sentry attaches to the next event      |
| `logs`        | `INFO`  | an entry in Sentry Logs                                                |

`OFF` sends none. So by default an `ERROR` entry is an event, a breadcrumb and a log, and an `INFO` entry a
breadcrumb and a log. Logs reach Sentry only when your app sets `options.logs.enabled = true`. Without it, the log
is skipped without an error.

The level in the sink's `SinkConfig` comes first: an entry below it reaches none of the three. An entry below all
three levels is written without sending anything, whether Sentry runs or not.

| entry         | event                                              | breadcrumb       | log                   |
|---------------|----------------------------------------------------|------------------|-----------------------|
| level         | its Sentry level (see below)                       | the same         | its Sentry log level  |
| time          | when it was logged (Apple: when the sink sends it) | the same         | the same              |
| tag           | the tag `tag`                                      | category         | attribute `tag`       |
| message       | the title, or extra `message` beside a throwable   | message          | body                  |
| fields        | extras as text, [tags and user](#on-a-server)      | data             | attributes            |
| `ResultError` | extra `error`, tag `error.type`          | data `error`, `error.type` | attributes `error`, `error.type` |
| throwable     | the exception                            |                  | attributes `exception.type`, `exception.message` |

On Android and the JVM an entry carries the time it was logged, so one replayed by `Log.holdUntilConfigured()` or
held under `SinkFailure.Retry` keeps its time. On Apple Sentry stamps it when the sink sends it, normally
milliseconds after it was logged. `error.type` is the error's `errorType`, its class name unless it
[names itself](#grouping-into-issues). `exception.type` is the throwable's class name, left out when the class has
none. `exception.message` is left out when the throwable has no message, and is `<message threw X>` when reading
it throws. No stack trace goes into a log.

`VERBOSE` is `debug` on an event and a breadcrumb and `trace` on a log. `DEBUG`, `INFO`, `WARN` and `ERROR` keep
their names, `WARN` being `warning` on an event and a breadcrumb. `WTF` is `fatal`.

Field values and the entry's `ResultError` read as [kern:logger renders them](../logger/README.md#fields): an array
or a collection as its contents, `[1, 2]`, also inside a list or a map. A container that holds itself reads as
`(this Collection)` or `(this Map)`. A `toString()` or an iteration that throws reads as `<toString() threw X>`, so
the entry still goes out. A log and a breadcrumb keep a field's type for a
`String`, `Int`, `Long`, `Short`, `Byte`, `Float`, `Double` or `Boolean`, a `Short` or a `Byte` going to a log as an
integer. Any other `Number` goes as its text. A `NaN` or an infinity goes as the text `NaN`, `Infinity` or
`-Infinity`, since Sentry cannot send it as a number. A field whose value is null is left out, since Sentry has no
place for it.

## Grouping into issues

On Android and the JVM the sink sets each event's fingerprint, so Sentry groups it into the right issue:

| the entry has     | fingerprint                   |
|-------------------|-------------------------------|
| a throwable       | none; Sentry groups by its stack |
| a `ResultError`   | `[tag, error.type]`, whatever the message |
| neither           | `[tag, message]`              |

Without one, an event with no throwable carries a thread dump with no frames of your app, and Sentry merges
unrelated errors into one issue. On Apple the multiplatform SDK has no way for the sink to set a fingerprint, so
set it in your own options:

```kotlin
Sentry.init { options ->
    options.dsn = "https://<key>@<org>.ingest.sentry.io/<project>"
    options.beforeSend = { event ->
        val tag = event.getTag("tag")
        val group = event.getTag("error.type") ?: event.message?.formatted
        if (tag != null && group != null && event.exceptions.isEmpty()) {
            event.fingerprint = mutableListOf(tag, group)
        }
        event
    }
}
```

The message is the issue's title. For a typed error, name the failure in the message, such as "payment declined",
or search by the `error.type` tag. Without a typed error the message also names the issue, so keep it constant and
put the values in fields:

```kotlin
Log.tag("Sync").e("fileId" to id) { "sync of $id failed" }   // one issue per file
Log.tag("Sync").e("fileId" to id) { "sync failed" }          // one issue; the id is a field
```

`error.type` is the error's `errorType`: its class's simple name, or `ResultError` for an anonymous object. Under
one tag, two hierarchies with a case of the same name share an issue, so
[namespace them](../result/README.md#declaring-your-errors). If `errorType` throws, `error.type` and the
fingerprint fall back to the class's simple name, losing a namespace your override adds.

On Android, R8 renames classes, so without a rule the default `errorType` becomes a name like `a`. These keep the
names of every class that implements `ResultError`, directly or through a sealed interface, and the attributes a
nested class such as `PaymentError.Declined` needs to report `Declined` rather than `PaymentError$Declined`:

```
-keepnames class * implements com.fromwau.kern.result.ResultError
-keepattributes InnerClasses,EnclosingMethod
```

An `errorType` written as a literal needs neither rule.

## Size limits

An entry takes at most 8 KiB on an event and on a log and 2 KiB on a breadcrumb. That is counted as the JSON
Sentry receives, keys and escapes included: the event's extras, the breadcrumb's data or the log's attributes,
with the keys the sink sets there. A log attribute costs more than an extra, so a log may keep fewer fields than
its event. A log also carries Sentry's own attributes on top, a few hundred bytes depending on your app: the SDK,
the server address, the user, the environment and the release. A `user.id` the sink sets from its
[`user` field](#on-a-server) counts toward the 8 KiB instead.

Over its budget, the longest values are cut first, each to the same size ending in `…(truncated)`, so short ones
such as ids and counts stay whole. No value is cut below 256 bytes, so one of 256 bytes or less is never cut, and
numbers and booleans never are. If cutting is not enough, as with thousands of fields or a huge key, the costliest
fields, key and value together, are left out first, the others keeping their order. One field `…` then says how
many, such as `12 fields left out` or `1 field left out`. An entry within its budget goes as it is.

The sink's own texts are never left out, and each keeps at least 1 KiB, or all of itself when shorter: the
`ResultError` as `error`, on an event the message beside a throwable, and on a log `exception.message`. Fields are
cut and left out before one goes below that, so on a breadcrumb the `ResultError` may take half the budget.

These are never cut: the tag, `error.type`, `exception.type`, the user id, and the message as an event's title, a
breadcrumb's message or a log's body. No value is cleaned. Where the tag and the types sit in the object they count
toward its budget, so a very long one leaves less room for the fields. Sentry drops a tag value with a newline.

Sentry rejects an event over 1 MB. An event carries up to 100 breadcrumbs, whose fields take at most 200 KiB
together; their messages are never cut. Nor is the exception an event is built from: the throwable's own message
and stack and those of its causes go whole. A very long message or a very deep chain of causes can still take an
event past Sentry's limit.

A field gives way where the sink sets the same key:

| destination      | keys where the sink's value wins                                                        |
|------------------|-----------------------------------------------------------------------------------------|
| event extras     | `error`; `message` beside a throwable; `…` when fields were left out                    |
| event tags       | `tag`, `error.type`, also when named in `tags`                                          |
| breadcrumb data  | `error`, `error.type`; `…` when fields were left out                                    |
| log attributes   | `tag`, `error`, `error.type`, `exception.type`, `exception.message`; `user.id` with the `user` field present; `…` when fields were left out |

Elsewhere it stays, so an event keeps a field `tag` as an extra and a breadcrumb keeps it as data. Sentry's SDK may
overwrite the keys it owns.

## Breadcrumbs and your own events

A breadcrumb exists once the sink has written its entry, on the sink's own worker. An error logged through kern
carries every earlier breadcrumb. Before your own `Sentry.capture*`, flush so the trail is complete:

```kotlin
Log.flush(1.seconds).onError { error -> Log.tag("App").w(error) { "breadcrumbs lost" } }
Sentry.captureException(e)
```

Sentry's own uncaught-exception handler does not flush the logger, so the event it sends may miss the last lines
still queued.

Removing or closing the sink leaves the trail as it is, so a reload that replaces the sink with a new instance
keeps it. `SentrySink.clearBreadcrumbs()` clears it:

| platform     | the sink's breadcrumbs go to | `clearBreadcrumbs()` clears                                       |
|--------------|------------------------------|-------------------------------------------------------------------|
| JVM, Android | Sentry's global scope        | only that scope; breadcrumbs your app adds with Sentry's API stay |
| Apple        | Sentry's current scope       | that scope, breadcrumbs your app added there included             |

Two `SentrySink`s that both take a level add each breadcrumb twice.

Keep the same `SentrySink` object across a reload: it keeps its queue and takes its new `SinkConfig`, as
[kern:logger](../logger/README.md#sinks) describes. Each new instance brings a queue of its own, up to `capacity`
entries, and a removed one drains beside it. The trail keeps the order breadcrumbs were added in, so while a removed
sink still writes its backlog, its lines and the new sink's interleave.

## On a server

On the JVM Sentry gives each thread its own scope. The sink captures each event and log on a fresh copy of Sentry's
root scope instead, so what your app set there reaches the sink's events and logs. What your app set on another
thread, such as the scope of a request, does not. On a sentry-java without the API this needs, the sink uses the
scope of its own thread instead and says so once on stderr:

```
kern.logger.sentry: this sentry-java has no Sentry.forkedRootScopes (NoSuchMethodError); events and logs now use the current thread's scope
```

To give the sink's events one user for the whole app:

| platform       | set the user                                                                               |
|----------------|--------------------------------------------------------------------------------------------|
| JVM            | on Sentry's global scope, with sentry-java's own API, or with `Sentry.setUser` on the thread that called `Sentry.init` |
| Android, Apple | with `Sentry.setUser` from any thread: Sentry keeps one scope for the whole app there      |

```kotlin
io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { scope ->
    scope.user = io.sentry.protocol.User().apply { id = userId }
}
```

That snippet is sentry-java's API, so it compiles only in JVM and Android code. Shared code needs your app's own
`expect`/`actual`, such as this sketch of app code:

```kotlin
expect fun setAppUser(id: String?)                        // commonMain
actual fun setAppUser(id: String?) = io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { scope ->
    scope.user = id?.let { io.sentry.protocol.User().apply { this.id = it } }
}                                                          // jvmMain
actual fun setAppUser(id: String?) = Sentry.setUser(id?.let { User(id = it) })  // androidMain, appleMain
```

On the JVM, `Sentry.setUser` on any other thread reaches only what that thread captures itself. For a user or other
values per request, pass them as fields and name the ones an event should carry:

```kotlin
SentrySink(tags = setOf("tenant"), user = "userId")

Log.tag("Checkout").e(failure, "tenant" to tenant, "userId" to userId) { "checkout failed" }
```

The event then carries the tag `tenant` and the user id. The log carries the same id as its `user.id`, so both name
the same user. The fields still go to the extras, the breadcrumb and the log.

The user id is the field's text as logged, never cut. A promoted tag reads as the event's extra does, then is cut to
Sentry's 200-character limit for a tag value, ending in `…`. A field whose value is null adds neither. Without the
field, the event and the log keep the user Sentry's scope gives them.

On the JVM the breadcrumb trail is in the global scope, shared by every request, so a multi-tenant server should set
`breadcrumbs = LogLevel.OFF`, or accept that every event carries other requests' lines.

## Consent

Consent is adding and removing the sink, not a setting on it:

```kotlin
fun grantConsent(dsn: String) {
    if (!SentrySink.sendsHere || crashReporting) return
    Sentry.init {
        it.dsn = dsn
        it.logs.enabled = true
    }
    Log.configure { it.copy(sinks = it.sinks + ConfiguredSink(SentrySink())) }
        .onError { error -> Log.tag("App").w(error) { "not logging to Sentry" } }
}

fun revokeConsent() {
    Log.configure { c -> c.copy(sinks = c.sinks.filterNot { it.sink is SentrySink }) }
        .onError { error -> Log.tag("App").w(error) { "still logging to Sentry" } }
    val flushed = Log.flush(5.seconds)
    if (flushed != Ok(Flushed.FullyWritten)) Log.tag("App").w { "revoke left $flushed" }
    SentrySink.clearBreadcrumbs()
    io.sentry.Sentry.configureScope(ScopeType.GLOBAL) { it.user = null }   // JVM and Android: sentry-java API
    Sentry.close()
}

val crashReporting: Boolean get() = Log.sinks.value.any { it.sink is SentrySink }
```

With the sink present only while Sentry runs, nothing fails before consent, and a second grant changes nothing.
Lines logged before consent are not sent. An app that wants them calls
[`Log.holdUntilConfigured()`](../logger/README.md#holding-startup-entries) at startup and ends the hold with
`Log.flush()` once the user has decided.

A removed sink takes no new entries, but still writes what it had queued. The flush waits for that, so what was
logged under consent goes out before `Sentry.close()`, and no later flush or close reports it as lost.

A flush that runs out of time returns `Ok(Flushed.StillQueued(...))`, not an error. What it still held is dropped
when Sentry closes, and every later close reports it as lost, so pick a timeout that fits what your app logs. Under
a flood, when the queue was full, the flush returns `Err(FlushError.Lost(...))` instead. The recipe's check warns in
both cases. A close may then also list `Dropped(NotEnabled)`: the logger's own warning about those entries, which
reached the sink after Sentry had closed.

After the flush, `SentrySink.clearBreadcrumbs()` clears the trail, so after a new grant events carry none from
before the revoke.

On the JVM a user your app set on Sentry's global scope outlives `Sentry.close()`. Without the line that clears it,
every event after a new grant still carries the old user. That line is sentry-java's API; in shared code, put it
behind your app's own `expect`/`actual`, as [On a server](#on-a-server) sketches. On Android, `Sentry.init` builds
fresh root scopes, so a user set with `Sentry.setUser` is gone after a new grant, while one on the global scope stays
as on the JVM. Android also saves the scope to disk and fills an ANR or a native crash from an earlier run, reported
after a restart, with the user saved there; whether a revoke clears that file is not verified. What Apple keeps
across `Sentry.close()` and a new `Sentry.init` is not verified.

## At exit

The sink never inits or closes Sentry. Close the logger first, then Sentry:

```kotlin
Log.close(5.seconds).onError { failed -> System.err.println("log entries lost: ${failed.errors}") }
Sentry.close()
```

Closed the other way round, Sentry is gone before the sink has written its last entries, and they fail with
`NotEnabled`.

## Testing what reaches Sentry

On the JVM, init with `Sentry.initWithPlatformOptions` to get sentry-java's `SentryOptions`, whose events carry
their extras, and record what the sink sends:

```kotlin
io.sentry.Sentry.getGlobalScope().clearBreadcrumbs()
io.sentry.Sentry.getGlobalScope().user = null
Sentry.initWithPlatformOptions { options ->
    options.dsn = "https://key@127.0.0.1/1"
    options.logs.isEnabled = true
    options.setBeforeSend { event, _ ->
        events += event
        null
    }
    options.setBeforeBreadcrumb { breadcrumb, _ ->
        breadcrumbs += breadcrumb
        breadcrumb
    }
    options.logs.setBeforeSend { log ->
        logs += log
        null
    }
}
```

Returning `null` keeps an event or a log from being sent. Return the breadcrumb, or the events captured after it
lose it from their trail. `Sentry.close()` keeps the global scope's breadcrumbs and user, and the next `init` replays
the breadcrumbs, so clear both before each init, as the first two lines do. Sentry is one global per process, so
these tests must not run in parallel, and each closes Sentry before the next starts.

## License

[Apache-2.0](../LICENSE).
