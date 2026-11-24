# result-coroutines

[`result`](../result/README.md) for coroutines and flows. Use it to catch exceptions in suspending code, to carry
results through a flow, and to run fallible work in parallel.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

```kotlin
import com.fromwau.kern.result.coroutines.*

// Result<String, NetworkError>
val one = suspendCatching(IOException::class, { NetworkError.Unknown(it) }) { http.get(city) }
// Flow<Result<Weather, NetworkError>>
repository.observe(city).asResult(IOException::class) { NetworkError.Unknown(it) }
// Result<List<Weather>, NetworkError>
val all = cities.parMapResult(concurrency = 4) { load(it) }
```

## Add to your build

```kotlin
dependencies {
    implementation("com.fromwau.kern:result-coroutines:$kernVersion")
}
```

- It brings `result` and kotlinx-coroutines with it.
- It comes from the same repository as [`result`](../result/README.md#add-to-your-build).
- Needs what [every kern module needs](../README.md#requirements).

## What each does

| function          | on                   | takes                                                        | gives                |
|-------------------|----------------------|--------------------------------------------------------------|----------------------|
| `suspendCatching` |                      | `caught`, `toError: (X) -> E`, a block that may suspend      | `Result<S, E>`       |
| `asResult`        | `Flow<S>`            | `caught`, `toError: (X) -> E`                                | `Flow<Result<S, E>>` |
| `mapSuccess`      | `Flow<Result<S, E>>` | `transform: suspend (S) -> T`                                | `Flow<Result<T, E>>` |
| `flatMapSuccess`  | `Flow<Result<S, E>>` | `transform: suspend (S) -> Result<T, E>`                     | `Flow<Result<T, E>>` |
| `parMapResult`    | `Iterable<T>`        | `concurrency: Int`, `transform: suspend (T) -> Result<S, E>` | `Result<List<S>, E>` |

- **`suspendCatching`** turns an exception of the type you name into your error. It is for third-party code that
  reports a failure by throwing. Name the narrowest type that code documents: a throw of any other type is not
  caught.
  - A cancelled coroutine stays cancelled. It is never turned into an error, whatever type you name.
  - A timeout inside the block, while the coroutine is still active, is an error when you name its type or a
    supertype of it, such as `CancellationException`.
- **`asResult`** makes each value a success. An exception of the type you name that the flow throws becomes one last
  error, and the flow completes. A throw of any other type ends the flow with that exception. It never catches
  exceptions thrown downstream, by your collector or by an operator such as `take`.
- **`mapSuccess`** and **`flatMapSuccess`** transform each success and pass errors through. Use `flatMapSuccess`
  for a step that returns a result, since `mapSuccess` would nest it.
- **`parMapResult`** runs the steps in parallel, at most `concurrency` at once. It gives their values in order.
  - The first error to arrive cancels the rest and is the result.
  - A cancelled step is cut wherever it is suspended. So use it for steps that are safe to abandon, such as lookups.

## A stream, end to end

Here is a price feed. It reconnects when the connection drops, survives a bad message, and enriches each price with
a lookup that can fail:

```kotlin
fun quotes(): Flow<Result<Quote, FeedError>> =
    reconnecting { socket.frames() }                                         // a cold flow
        .asResult(IOException::class) { FeedError.Down(it) }                 // still failing after retries: ends it
        .flatMapSuccess { raw ->                                             // a bad frame is one item
            catching(SerializationException::class, { FeedError.Malformed(raw) }) { parse(raw) }
        }
        .flatMapSuccess { price -> lookup(price.symbol).map { Quote(price, it) } }

fun reconnecting(source: () -> Flow<String>): Flow<String> = flow {
    var drops = 0
    emitAll(source().onEach { drops = 0 }.retryWhen { cause, _ ->
        cause is IOException && drops++ < 3 && run { delay(100L * drops); true }        // 3 tries per drop
    })
}
```

- Retry before `asResult`, where the failure is still an exception. After it, the failure is a value, and a
  `retry` never runs.
- `retryWhen`'s own `attempt` never resets, so it caps a long-lived stream's lifetime. The `drops` counter above
  starts over with each message that gets through.
- Every step after `asResult` must return a result. One that throws ends the stream.

## License

[Apache-2.0](../LICENSE).
