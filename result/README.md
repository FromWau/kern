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

It depends on the Kotlin standard library and nothing else. Serializing a result is an optional extra, and
the section below is the only place that changes.

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
| `flatMap`     | `(S) -> Result<T, E>`  | chains a next step that can itself fail                      |
| `mapError`    | `(E) -> F`             | transforms an error, passes a success through                |
| `fold`        | `(S) -> T`, `(E) -> T` | collapses both cases into one type                           |
| `getOrElse`   | `(E) -> S`             | the value, or what the fallback makes of the error           |
| `getOrNull`   |                        | the value, or null                                           |
| `errorOrNull` |                        | the error, or null                                           |
| `orElse`      | `(E) -> Result<S, F>`  | recovers with another attempt, which may fail its own way    |
| `onSuccess`   | `(S) -> Unit`          | runs a side effect on a value, returns the result unchanged  |
| `onError`     | `(E) -> Unit`          | runs a side effect on an error, returns the result unchanged |

Three of them run on a success value and differ in what they leave you holding:

```kotlin
findUserById(id).map { it.name }        // Result<String, CrudError>, the step cannot fail
findUserById(id).flatMap { save(it) }   // Result<User, CrudError>, save may fail
findUserById(id).onSuccess { log(it) }  // Result<User, CrudError>, unchanged
```

Only `flatMap` can turn a success into an error, because it returns whatever the second step returned.
Reach for it wherever `map` would leave you holding a `Result` inside a `Result`.

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

## Sending one over a wire

A result is `@Serializable`, so it crosses a wire as itself with nothing to annotate at the call site. The
failure arrives as the case you declared rather than as a sentence someone has to parse back.

Serialization is an **optional dependency**, split the way `kotlinx-datetime` splits it. On JVM and
Android nothing is pulled in, so a project that never serializes a result resolves only the standard
library. The native targets do resolve `kotlinx-serialization-core`, because Kotlin/Native has no lazy
class loading and a dependent's own compilation needs it present.

Add the format runtime to your own build when you need it:

```kotlin
plugins {
    kotlin("plugin.serialization")
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:$serializationVersion")
}
```

`IError` stays a plain marker, so it is your own error hierarchy that carries the annotation. That is the
same work you would do for any type of yours that crosses a wire:

```kotlin
@Serializable
sealed interface CrudError : IError {
    @Serializable @SerialName("not_found") data class NotFound(val id: Long) : CrudError
}

@Serializable
data class Response(val user: Result<User, CrudError>)
```

```json
{"user":{"error":{"type":"not_found","id":7}}}
{"user":{"success":"ada"}}
```

A result is an object holding exactly one of `success` or `error`, and one holding neither is refused
rather than guessed at. Nothing on the wire is named after a class here, so moving or renaming a package
cannot change what your services already exchange. The shape is a plain two-field structure rather than
anything JSON-specific, so CBOR and protobuf encode it too.

## License

[Apache-2.0](../LICENSE).
