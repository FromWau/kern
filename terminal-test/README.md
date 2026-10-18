# terminal-test

A test `Terminal` for [`kern:terminal`](../terminal/README.md). `RecordingTerminal` keeps what each stream was sent,
answers the width, colour and tty questions with what you built it with, and fails a stream's writes when you tell
it to.

**Targets:** the same as [`kern:terminal`](../terminal/README.md).

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:terminal-test:$kernVersion")
}
```

KMP consumers put it in `commonTest`. `kern:terminal` comes along as `api`. `RecordingTerminal` is in the package
`com.fromwau.kern.terminal.test`. Needs what [every kern module needs](../README.md#requirements).

## Checking what your code writes

Hand a `RecordingTerminal` to the code under test, then read `out` and `err`, each the text that stream was sent:

```kotlin
import com.fromwau.kern.terminal.*
import com.fromwau.kern.terminal.test.RecordingTerminal
import kotlin.test.*

@Test
fun `a missing file is reported in red`() {
    val terminal = RecordingTerminal(errAnsi = true)

    report(terminal, missing = "notes.txt")

    assertEquals("", terminal.out)
    assertEquals("${Char(27)}[31mnotes.txt is missing${Char(27)}[0m\n", terminal.err)
}
```

Each parameter is what the property of the same name answers, and each defaults to what a test that captures output should claim:

| parameter       | default | what it answers                          |
|-----------------|---------|------------------------------------------|
| `columns`       | `0`     | the width, `0` meaning "do not wrap"     |
| `ansi`          | `false` | whether standard output takes colour     |
| `errAnsi`       | `false` | whether standard error takes colour      |
| `outIsTerminal` | `false` | whether standard output is a terminal    |
| `errIsTerminal` | `false` | whether standard error is a terminal     |

Writes from several threads are kept in the order they were made, so a logger writing from its own thread can
use one too.

## Failing a stream

`failWith(error)` fails every later write to `error.stream`: nothing more is recorded for that stream, and
`writeResult()`, `writeResult(stream)`, `afterWrites` and `awaitWriteResult()` report the error from then on. What
was written before stays in `out` or `err`.

`afterWrites` runs its block at once on every target, Node included, since a `RecordingTerminal` has no writes to
wait for. A call from inside a block runs once that block returns, as on the real terminal.

```kotlin
import kotlinx.coroutines.test.runTest

@Test
fun `a closed pipe exits with the broken pipe code`() = runTest {
    val terminal = RecordingTerminal()
    terminal.failWith(WriteError.BrokenPipe(Stream.Out))

    terminal.out("lost\n")

    assertEquals(BROKEN_PIPE_EXIT, finish(terminal))
    assertEquals("", terminal.out)
}
```

`finish` here is the suspending failure handling from the
[kern:terminal README](../terminal/README.md#failed-writes), so the test runs in `runTest`, from
`org.jetbrains.kotlinx:kotlinx-coroutines-test` at the version matching your kotlinx-coroutines. `writeResult()`
reports standard output's failure before standard error's, as a real terminal does. A second `failWith` for the same
stream replaces the first.

## License

[Apache-2.0](../LICENSE).
