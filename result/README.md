# result

A typed-error `Result<S, E>` for Kotlin Multiplatform.

Some failures are expected: a user is missing, a form is invalid, a server is down. With `result` such a failure is
a value, not a thrown exception, so the caller has to handle it to get at the value. It carries your own error type
instead of a message string, so its payload arrives intact.

**Targets:** JVM, Android, js, wasmJs, wasmWasi, linuxX64, linuxArm64, mingwX64, macosArm64, iosArm64,
iosSimulatorArm64, tvosArm64, tvosSimulatorArm64, watchosArm64, watchosSimulatorArm64, watchosDeviceArm64,
androidNativeArm64, androidNativeX64, androidNativeArm32, androidNativeX86.

```kotlin
import com.fromwau.kern.result.*

fun find(id: Long): Result<User, CrudError> = Err(CrudError.NotFound(id))       // produce: Ok(value) or Err(error)
find(id).map { it.name }.getOrElse { "unknown" }                                // consume: operators, or a `when`
result { save(find(id).bind().copy(name = name)).bind() }                       // chain: the first error ends it
resultAll { val a by checkA(raw).collect(); val b by checkB(raw).collect(); Pair(a, b) }   // every error at once
val (good, bad) = lines.map(::parse).partition()                                // keep what succeeded
catching({ CrudError.DbError(it) }) { dao.findById(id) }                        // an exception becomes an error
```

## Add to your build

Releases are on [maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/result). It needs no
credentials and lists every version.

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:result:$kernVersion")  // KMP: in commonMain
}
```

- Needs what [every kern module needs](../README.md#requirements).
- On the JVM and Android, `result` depends on nothing but the Kotlin standard library.
- On every other target it also brings `kotlinx-serialization-core`.
- For coroutines and flows, add [`result-coroutines`](../result-coroutines/README.md).

Everything is in `com.fromwau.kern.result`. Import it with `import com.fromwau.kern.result.*`. Kotlin imports its own
`kotlin.Result` by default, so a file without the import gets that one instead.

## Declaring your errors

Every error implements `ResultError`. Seal one hierarchy per concern. Give it a case per failure, with data as its
payload:

```kotlin
sealed interface CrudError : ResultError {
    data class NotFound(val id: Long) : CrudError
    data class Invalid(val reason: String) : CrudError
    data class DbError(val cause: Throwable) : CrudError
}
```

A `when` over it is exhaustive, so a failure you add later is a compile error wherever a caller has to react.
Avoid a single case that carries a message string: a caller can only print it, not act on it.

Each error has an `errorType`. That is the name a crash reporter or a search groups it by. By default it is the
class's simple name, so `CrudError.NotFound(3)` has `NotFound`. Two hierarchies with a case of the same name share
it. To keep them apart, namespace every case of a hierarchy in one line:

```kotlin
sealed interface PaymentError : ResultError {
    override val errorType: String get() = "Payment.${this::class.simpleName}"

    data object Declined : PaymentError
}
```

A class's name changes when you rename it. Code shrinking such as R8 also replaces it with a short one like `a`.
Where the type must survive both, give each case a literal instead:

```kotlin
data object Declined : PaymentError {
    override val errorType: String get() = "Payment.Declined"
}
```

## Producing one

`Ok` and `Err` are short for `Result.Success` and `Result.Error`:

```kotlin
fun findUserById(id: Long): Result<User, CrudError> {
    if (id <= 0) return Err(CrudError.Invalid("id must be positive"))
    val user = catching({ CrudError.DbError(it) }) { dao.findById(id) }.getOrElse { return Err(it) }
    return user.toResult { CrudError.NotFound(id) }
}
```

- `catching(toError) { }` turns an exception into your error. It rethrows cancellation.
- The block of `catching` cannot suspend. In suspending code use `suspendCatching` from `result-coroutines`.
- `toResult { error }` turns a nullable value into a result.
- `EmptyResult<E>` is `Result<Unit, E>`, for work with nothing to hand back. Its success is `Ok(Unit)`.

## Consuming one

The plainest way is a `when`:

```kotlin
when (val result = findUserById(id)) {
    is Result.Success -> render(result.value)
    is Result.Error -> report(result.error)
}
```

Or use the operators. Each one runs only on the case it names:

| operator        | takes                                      | does                                                         |
|-----------------|--------------------------------------------|--------------------------------------------------------------|
| `map`           | `(S) -> T`                                 | transforms a success value                                   |
| `flatMap`       | `(S) -> Result<T, E>`                      | chains a next step that can itself fail                      |
| `flatten()`     |                                            | unwraps a `Result<Result<S, E>, E>` into a `Result<S, E>`    |
| `flatten`       | `(E) -> F`                                 | unwraps a `Result<Result<S, F>, E>`, mapping the outer error |
| `mapError`      | `(E) -> F`                                 | transforms an error, for example across a layer boundary     |
| `fold`          | `onSuccess: (S) -> T`, `onError: (E) -> T` | collapses both cases into one value                          |
| `getOrElse`     | `(E) -> S`                                 | the value, or a fallback made from the error                 |
| `getOrNull()`   |                                            | the value, or null                                           |
| `errorOrNull()` |                                            | the error, or null                                           |
| `orElse`        | `(E) -> Result<S, F>`                      | recovers with another attempt                                |
| `onSuccess`     | `(S) -> Unit`                              | a side effect on a value; returns the result unchanged       |
| `onError`       | `(E) -> Unit`                              | a side effect on an error; returns the result unchanged      |
| `isSuccess()`   |                                            | true for a success, and smart casts to it                    |
| `isError()`     |                                            | true for an error, and smart casts to it                     |

The operators are inline, so their lambdas may suspend wherever the call may. Use `map` for a step that cannot fail
and `flatMap` for one that returns a result:

```kotlin
findUserById(id).map { it.name }        // Result<String, CrudError>
findUserById(id).flatMap { save(it) }   // Result<User, CrudError>
findUserById(id).map { save(it) }       // Result<Result<User, CrudError>, CrudError>: you wanted flatMap
```

`Result` is covariant, so a `Result<User, CrudError.NotFound>` is a `Result<User, CrudError>`. Convert an error from
another hierarchy with `mapError`:

```kotlin
val shown: Result<User, ApiError> = findUserById(id).mapError { crud ->
    when (crud) {
        is CrudError.NotFound -> ApiError.Missing("no user ${crud.id}")
        is CrudError.Invalid -> ApiError.Missing(crud.reason)
        is CrudError.DbError -> ApiError.Unavailable
    }
}
```

## Several steps in a row

A `result` block runs steps that can each fail, top to bottom. `bind()` gives a success's value. The first error
ends the block and becomes its result:

```kotlin
fun register(form: Form): Result<User, SignupError> = result {
    if (form.age < 18) Err(SignupError.TooYoung).bind()       // end the block with an error of your own
    val email = validateEmail(form.email).bind { SignupError.Email(it) }   // convert another error type
    val user = createUser(email).bind()
    sendWelcome(user).bind()
    user
}
```

Declare the return type of a function whose body is a `result` block. The compiler then checks the error type and
the block's last expression.

Without a block, `getOrElse { return Err(it) }` does the same, since `getOrElse` is inline:

```kotlin
val email = validateEmail(form.email).getOrElse { return Err(SignupError.Email(it)) }
```

### Details

- The block may suspend.
- A failing `bind` in another coroutine that runs while the block does still ends the block with its error. That
  covers one in a nested `runBlocking` or a `launch` the block joins.
- A `bind` run after the block returned throws `IllegalStateException`.
- `bind` leaves the block by throwing a signal that is not an `Exception`, so your `catch (e: Exception)` lets it
  through. Swallowing it with `catch (t: Throwable)` or `runCatching` cannot make the result a success.

## Several results at once

Sometimes you have a list of results, or a list of items to check. Pick by what should happen on an error:

| function           | on                       | gives                            | on an error                    |
|--------------------|--------------------------|----------------------------------|--------------------------------|
| `combine`          | `Iterable<Result<S, E>>` | `Result<List<S>, E>`             | the first one                  |
| `mapResult`        | `Iterable<T>`            | `Result<List<R>, E>`             | stops at the first one         |
| `mapResultPartial` | `Iterable<T>`            | `Result<List<R>, Partial<R, E>>` | stops, keeping what was done   |
| `mapResultAll`     | `Iterable<T>`            | `Result<List<R>, AllErrors<E>>`  | runs every item, keeps all     |
| `partition`        | `Iterable<Result<S, E>>` | `Pair<List<S>, List<E>>`         | splits values from errors      |
| `zip`              | two or three results     | `Result<T, E>`                   | the first one                  |
| `zipAll`           | two or three results     | `Result<T, AllErrors<E>>`        | every one                      |

```kotlin
val ports = listOf("80", "443").mapResult(::parsePort)                         // Result<List<Int>, PortError>
val entries = lines.withIndex().mapResultAll { (i, line) ->                    // every bad line, numbered
    parseLine(line).mapError { RowError(i + 1, it) }
}
val (orders, rejected) = lines.map(::parseLine).partition()                    // import the good, report the bad
val saved = orders.mapResultPartial { save(it) }                               // on failure: Partial(done, error)
val config = zipAll(checkPort(raw.port), checkHost(raw.host)) { port, host -> Config(port, host) }
```

`AllErrors(errors)` and `Partial(done, error)` are `ResultError`s themselves, so they convert with `mapError` like
any other error.

### Collecting every error in a block

A form is the usual case: you want every bad field reported, not just the first. When the steps do not fit one
lambda, collect them in a `resultAll` block. `collect()` records a step's error and hands its value to a `by`
property:

```kotlin
fun validate(form: WatchlistForm): Result<Watchlist, AllErrors<FieldError>> = resultAll {
    val name by checkName(form.name).collect()
    val symbols = form.symbols.map { checkSymbol(it).collect() }   // a list, item by item
    val min by checkSeconds(form.min).collect()
    val max by checkSeconds(form.max).collect()
    if (min > max) Err(FieldError.Range).collect()                 // a rule across fields goes last
    Watchlist(name, symbols.map { it.value }, min..max)
}
// Err(AllErrors(listOf(FieldError.BadName(""), FieldError.BadSymbol("??"))))   every error, in order
```

Collect every step before you read any. Reading a failed step ends the block, so steps collected after that read
never run. `bind()` works here too, and ends the block at once.

## Sending one over a wire

A result is `@Serializable` when its value and error types are. kotlinx-serialization is an optional dependency. To
use it, add the plugin and a format to your own build, and annotate your error hierarchy.

```kotlin
@Serializable
sealed interface CrudError : ResultError {
    @Serializable @SerialName("not_found") data class NotFound(val id: Long) : CrudError
}

@Serializable
data class Response(val user: Result<User, CrudError>)
```

```json
{"user":{"error":{"type":"not_found","id":7}}}
{"user":{"success":"ada"}}
```

### Sharp edges

- A missing annotation on an error case fails only at runtime, when such an error is encoded, so test the error
  case of anything you serialize.
- A case that holds a `Throwable` cannot be serialized. Map it to one that can before encoding.

## Must-use results

A result nobody looks at is a failure nobody handles. Kotlin's return value checker turns a dropped result into a
warning:

```kotlin
kotlin {
    compilerOptions { freeCompilerArgs.add("-Xreturn-value-checker=full") }
}
```

- `full` covers your own functions too. That is what an application wants: `repository.save(order)` on a line of
  its own warns.
- A library can use `check` instead, and mark its own declarations with `@MustUseReturnValues`.

`onSuccess`, `onError`, `bind` and `collect` are exempt. So are `result-test`'s `assertSuccess` and `assertError`.
`onSuccess` and `onError` are for handling one side and letting the rest go, as in
`fireAndForget().onError { log(it) }`.

## Testing

`result-test` adds assertions. Add it beside `result`, in `commonTest` for KMP:

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:result-test:$kernVersion")
}
```

The assertions live in `com.fromwau.kern.result`, the package of `result` itself, so the same
`import com.fromwau.kern.result.*` brings them in:

```kotlin
val user = findUser(7).assertSuccess()                                 // the value, or a failure naming the error
findUser(8).assertError { it is CrudError.NotFound && it.id == 8L }    // the error, checked by the compiler
assertEquals(Err(CrudError.NotFound(8)), findUser(8))                  // an exact result, with kotlin-test
```

`assertError { it is F }` returns the error as the result's error type `E`, not as `F`, so the case's fields are not
in reach afterwards. To read them, use `assertError<F>()`, which returns `F`:

```kotlin
val error: CrudError = findUser(8).assertError { it is CrudError.NotFound }    // E: no .id here
val missing = findUser(8).assertError<CrudError.NotFound>()                    // F: missing.id is 8
```

`F` is not checked against the result's error type, so a case the result can never hold compiles and fails when run.
Prefer `assertError { }` when the check alone is enough.

## Pitfalls

- **A result inside a result.** A `result { }` block, `map` or `mapSuccess` whose last step returns a result nests
  it. An inferred type hides that. Bind the last step, use `flatMap`, and declare return types.
- **Two unrelated error types meet at `ResultError`.** `price().flatMap { lookup(it) }` with two different error
  hierarchies infers `Result<Quote, ResultError>`. A declared return type turns that into a compile error. Convert
  with `mapError`.
- **`flatten()` across two error types.** `flatten()` takes one error type for both levels. With a different one
  inside and outside, Kotlin infers their common supertype, such as `ResultError`, as the error. Convert one side
  first.
  - The inner side: `map { it.mapError(...) }`.
  - The outer side: `flatten(AppError::File)`. It wraps an outer `FileError` in a case of the inner `AppError`.
- **`kotlin.Result` and `kotlin.Error`.** Without the star import, `Result` is Kotlin's own, and a bare `Error(...)`
  is `kotlin.Error`. Use `Ok` and `Err`.
- **A forgotten `.collect()`.** `if (bad) Err(e)` in a `resultAll` block is a value nobody reads. Only the
  must-use checker reports it.
- **Timeouts.** A `withTimeout` that runs out inside `result { }` ends the block with its exception. Wrap it in
  `suspendCatching` to make it an error.

## Coming from Arrow

| Arrow                                | here                                                   |
|--------------------------------------|--------------------------------------------------------|
| `Either<E, A>`, `Left`, `Right`      | `Result<A, E>`, `Err`, `Ok`: the value type comes first |
| `either { }`, `bind()`               | `result { }`, `bind()`                                 |
| `raise(e)`, `ensure(cond) { e }`     | `Err(e).bind()`, `if (!cond) Err(e).bind()`            |
| `Either.catch { }`                   | `catching { }`, `suspendCatching { }`                  |
| `zipOrAccumulate`, `mapOrAccumulate` | `zipAll`, `mapResultAll`, `resultAll { }`              |
| `recover`, `mapLeft`                 | `orElse`, `mapError`                                   |
| `parMap`                             | `parMapResult` in `result-coroutines`                  |

## License

[Apache-2.0](../LICENSE).
