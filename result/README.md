# result

A typed-error `Result<S, E>` for Kotlin Multiplatform.

An expected failure is a value, not a thrown exception. It carries your own error type instead of a
message string, so the failure leaves your data layer with its payload intact. A caller has to handle it
to reach the success value.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

Releases are published as `com.fromwau.kern:result` to
[maven.frommhund.xyz](https://maven.frommhund.xyz/#/releases/com/fromwau/kern/result), which needs no
credentials to read. That page lists every published version, and
[`maven-metadata.xml`](https://maven.frommhund.xyz/releases/com/fromwau/kern/result/maven-metadata.xml)
names the latest.

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:result:$kernVersion")
}
```

KMP consumers put it in `commonMain`. The JVM and Android artifacts are Java 25 bytecode, so those two
need a JDK 25 toolchain; the native targets have no such requirement.

Sources are published for every target, so your IDE answers an API question with quick-doc and step-into
rather than a decompiled stub.

## Why not exceptions

An exception is right for a bug: a state your program was never supposed to reach. It is the wrong tool
for the failures you already know about, because nothing in a signature says which ones a function throws
and nothing makes a caller handle them. Those failures are ordinary outcomes, so model them as data.

An error string is the other half of the mistake. The moment your data layer returns `"user not found"`,
it has decided what the user reads and in which language, from the wrong layer. Return a type instead and
let the edge decide the words.

## Declaring your errors

Every error implements `IError`, and a sealed hierarchy per concern is the shape that pays off:

```kotlin
sealed interface CrudError : IError {
    data class NotFound(val id: Long) : CrudError
    data class Invalid(val reason: String) : CrudError
    data class DbError(val cause: Throwable) : CrudError
}
```

Because the root is sealed, a `when` over it is exhaustive. Add a fourth failure later and every site that
has to react becomes a compile error rather than a silent fall-through.

The failure mode to avoid is not returning a string. It is wrapping one:

```kotlin
// no: a string in a box
data class ConverterError(val message: String) : IError
```

That satisfies the type checker and changes nothing, because a caller can still only print it. Name the
case instead, and let the payload be data:

```kotlin
// yes
sealed interface PortError : IError {
    data class NotANumber(val given: String) : PortError
    data class OutOfRange(val given: Int) : PortError
}
```

The sharp version of the rule: **a string field on a case is fine, a string field as the hierarchy's
contract is not.** `Invalid(val reason: String)` above sits beside cases carrying real data, so the type as
a whole still carries data. An interface that every error must implement, declaring `val message: String`,
is the same string in a box one level up. It makes each implementer a message carrier by definition.

## Producing one

`Ok` and `Err` are typealiases for `Result.Success` and `Result.Error`:

```kotlin
suspend fun findUserById(id: Long): Result<User, CrudError> {
    if (id <= 0) return Err(CrudError.Invalid("id must be positive"))

    return try {
        val user = dao.findById(id)
        if (user == null) Err(CrudError.NotFound(id)) else Ok(user)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive()
        Err(CrudError.DbError(e))
    }
}
```

The `ensureActive()` call is what keeps cancellation working. Without it a canceled coroutine has its
`CancellationException` swallowed into a `DbError` and the scope never learns it should stop. Put it in
every `catch` that turns a throwable into an error.

Prefer `Ok` and `Err` at call sites over the nested names, and avoid `import com.fromwau.kern.result.Result.*`.
A bare `Error(...)` collides with `kotlin.Error`, which is a `Throwable`. A missing import then surfaces as
a confusing overload error rather than an unresolved name, and compiles silently on any argument that
happens to fit.

When the work has nothing to hand back on success, `EmptyResult<E>` says so. It is `Result<Unit, E>`, so
the success case is still there and is written `Ok(Unit)`:

```kotlin
suspend fun deleteUserById(id: Long): EmptyResult<CrudError> =
    findUserById(id).map { dao.delete(it) }
```

## Consuming one

```kotlin
when (val result = findUserById(id)) {
    is Result.Success -> render(result.value)
    is Result.Error -> report(result.error)
}
```

Or with the operators, each of which runs its lambda only on the case it names:
| operator      | signature              | does                                                         |
|---------------|------------------------|--------------------------------------------------------------|
| `map`         | `(S) -> T`             | transforms a success value, passes an error through          |
| `mapError`    | `(E) -> F`             | transforms an error, passes a success through                |
| `fold`        | `(S) -> T`, `(E) -> T` | collapses both cases into one type                           |
| `getOrElse`   | `(E) -> S`             | the value, or what the fallback makes of the error           |
| `getOrNull`   |                        | the value, or null                                           |
| `errorOrNull` |                        | the error, or null                                           |
| `orElse`      | `(E) -> Result<S, F>`  | recovers with another attempt, which may fail its own way    |
| `onSuccess`   | `(S) -> Unit`          | runs a side effect on a value, returns the result unchanged  |
| `onError`     | `(E) -> Unit`          | runs a side effect on an error, returns the result unchanged |

`mapError` is how an error crosses a layer boundary. The data layer's `CrudError` becomes whatever the
layer above speaks, and the exhaustive `when` means you cannot forget a case:

```kotlin
val shown: Result<User, ApiError> = findUserById(id).mapError { crud ->
    when (crud) {
        is CrudError.NotFound -> ApiError.Missing("no user ${crud.id}")
        is CrudError.Invalid -> ApiError.Missing(crud.reason)
        is CrudError.DbError -> ApiError.Unavailable
    }
}
```

## License

[Apache-2.0](../LICENSE).
