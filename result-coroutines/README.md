# result-coroutines

[`result`](../result/README.md) for coroutines and flows: catching in suspending code, flows of results, and
fallible work run in parallel.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

```kotlin
import com.fromwau.kern.result.coroutines.*

val one = suspendCatching({ NetworkError.Unknown(it) }) { http.get(city) }   // Result<String, NetworkError>
repository.observe(city).asResult { NetworkError.Unknown(it) }               // Flow<Result<Weather, NetworkError>>
val all = cities.parMapResult(concurrency = 4) { load(it) }                  // Result<List<Weather>, NetworkError>
```

## Add to your build

```kotlin
dependencies {
    implementation("com.fromwau.kern:result-coroutines:$kernVersion")
}
```

It brings `result` and kotlinx-coroutines with it, from the same repository as
[`result`](../result/README.md#add-to-your-build). Needs what [every kern module needs](../README.md#requirements).

## What each does

| function          | on                   | takes                                                        | gives                |
|-------------------|----------------------|--------------------------------------------------------------|----------------------|
| `suspendCatching` |                      | `toError: (Exception) -> E`, a block that may suspend        | `Result<S, E>`       |
| `asResult`        | `Flow<S>`            | `toError: (Exception) -> E`                                  | `Flow<Result<S, E>>` |
| `mapSuccess`      | `Flow<Result<S, E>>` | `transform: suspend (S) -> T`                                | `Flow<Result<T, E>>` |
| `flatMapSuccess`  | `Flow<Result<S, E>>` | `transform: suspend (S) -> Result<T, E>`                     | `Flow<Result<T, E>>` |
| `parMapResult`    | `Iterable<T>`        | `concurrency: Int`, `transform: suspend (T) -> Result<S, E>` | `Result<List<S>, E>` |

- **`suspendCatching`** turns an exception into your error. A cancelled coroutine stays cancelled and is never
  turned into an error; a timeout inside the block is an error like any other.
- **`asResult`** makes each value a success. An exception the flow throws becomes one last error, and the flow
  completes. Exceptions thrown downstream, by your collector or an operator such as `take`, are never caught.
- **`mapSuccess`** and **`flatMapSuccess`** transform each success and pass errors through. Use `flatMapSuccess`
  for a step that returns a result; `mapSuccess` would nest it.
- **`parMapResult`** runs the steps in parallel, at most `concurrency` at once, and gives their values in order.
  The first error to arrive cancels the rest and is the result. A cancelled step is cut wherever it is suspended,
  so use it for steps that are safe to abandon, such as lookups.

## A stream, end to end

A price feed that reconnects when the connection drops, survives a bad message, and enriches each price with a
lookup that can fail:

```kotlin
fun quotes(): Flow<Result<Quote, FeedError>> =
    reconnecting { socket.frames() }                                         // a cold flow
        .asResult { FeedError.Down(it) }                                     // still failing after retries: ends it
        .flatMapSuccess { raw -> catching({ FeedError.Malformed(raw) }) { parse(raw) } }   // a bad frame is one item
        .flatMapSuccess { price -> lookup(price.symbol).map { Quote(price, it) } }

fun reconnecting(source: () -> Flow<String>): Flow<String> = flow {
    var drops = 0
    emitAll(source().onEach { drops = 0 }.retryWhen { cause, _ ->
        cause is IOException && drops++ < 3 && run { delay(100L * drops); true }        // 3 tries per drop
    })
}
```

- Retry before `asResult`, where the failure is still an exception. After it, the failure is a value and a
  `retry` never runs.
- `retryWhen`'s own `attempt` never resets, so it caps a long-lived stream's lifetime. The `drops` counter above
  starts over with each message that gets through.
- Every step after `asResult` must return a result: one that throws ends the stream.

## License

[Apache-2.0](../LICENSE).
