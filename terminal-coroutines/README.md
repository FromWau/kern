# terminal-coroutines

[`kern:terminal`](../terminal/README.md) for coroutine code: `outEach` writes a `Flow` to standard output and stops
collecting it at the first failed write there, so a long output ends soon after the reader of a pipe goes away.

```kotlin
import com.fromwau.kern.terminal.coroutines.outEach

val written = terminal.outEach(lines.map { "$it\n" })   // EmptyResult<WriteError>
```

**Targets:** the same as [`kern:terminal`](../terminal/README.md).

## Add to your build

```kotlin
dependencies {
    implementation("com.fromwau.kern:terminal-coroutines:$kernVersion")
}
```

It brings `kern:terminal` and kotlinx-coroutines with it. Needs what
[every kern module needs](../README.md#requirements).

## How it behaves

It writes each piece as given, with no newline added. Pieces are joined into writes of about 64 K characters, and a
longer piece is written alone, never split. `Styled` pieces are coloured when `terminal.ansi` is true. After each
write it waits with `awaitWriteResult()`, so under Node too a failure is seen before the next write.

It returns success after the last piece, also for an empty flow, or a failure, as `awaitWriteResult()` answers it.
When a write to standard output fails, the flow is cancelled: the piece that did not fit in that write is the last
one collected. A failed standard error does not stop the output and is reported at the end. If the flow or a write
throws, the exception reaches you. Cancelling the caller cancels the writing.

For code that does not suspend, `kern:terminal` has `outEach` for a `Sequence`, which calls a block when it is done.

## License

[Apache-2.0](../LICENSE).
