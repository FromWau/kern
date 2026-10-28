# terminal-test

A test `Terminal` for [`kern:terminal`](../terminal/README.md).

Code that prints is worth testing: the right text, on the right stream, coloured or not, and the right exit code when
a pipe closes. `RecordingTerminal` keeps what each stream was sent. It answers the width, colour and tty questions
with what you built it with, and standard input holds the bytes you give it. And it fails a stream's writes, or the
read, when you tell it to.

**Targets:** the same as [`kern:terminal`](../terminal/README.md).

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:terminal-test:$kernVersion")
}
```

- KMP consumers put it in `commonTest`.
- `kern:terminal` comes along as `api`.
- `RecordingTerminal` is in the package `com.fromwau.kern.terminal.test`.
- Needs what [every kern module needs](../README.md#requirements).

## Checking what your code writes

Hand a `RecordingTerminal` to the code under test. Then read `out` and `err`, each the text that stream was sent:

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

Each parameter is what the property of the same name answers. Each defaults to what a test that captures output
should claim:

| parameter       | default | what it answers                          |
|-----------------|---------|------------------------------------------|
| `columns`       | `0`     | the width, `0` meaning "do not wrap"     |
| `ansi`          | `false` | whether standard output takes colour     |
| `errAnsi`       | `false` | whether standard error takes colour      |
| `outIsTerminal` | `false` | whether standard output is a terminal    |
| `errIsTerminal` | `false` | whether standard error is a terminal     |
| `inIsTerminal`  | `false` | whether standard input is a terminal     |

Writes from several threads are kept in the order they were made. So a logger writing from its own thread can use
one too.

## Checking the order of both streams

`out` and `err` lose how the two streams interleave. `writes` keeps it: every write to either stream, in the order it
was made, as `2>&1` shows them. Each is a `Write(stream, text)`:

```kotlin
@Test
fun `an error shows between the progress lines around it`() {
    val terminal = RecordingTerminal()

    copy(terminal, listOf("a.txt", "missing.txt", "b.txt"))

    assertEquals(
        listOf(
            Write(Stream.Out, "copied a.txt\n"),
            Write(Stream.Err, "error: missing.txt is missing\n"),
            Write(Stream.Out, "copied b.txt\n"),
        ),
        terminal.writes,
    )
}
```

- `copy` here is the code under test: it prints `copied <file>` to `out` and the error to `err`.
- `Write` is in the package `com.fromwau.kern.terminal.test`, next to `RecordingTerminal`.
- Each read of `writes` is a copy, so a later write does not change a list you already hold.

## Failing a stream

`failWith(error)` fails every later write to `error.stream`:

- Nothing more reaches `out`, `err` or `writes` for that stream. Those writes go to `lostWrites` instead.
- `writeResult()`, `writeResult(stream)`, `afterWrites` and `awaitWriteResult()` report the error from then on.
- What was written before stays in `out` or `err`.

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

`finish` here is the suspending failure handling from the [kern:terminal README](../terminal/README.md#failed-writes).
So the test runs in `runTest`, from `org.jetbrains.kotlinx:kotlinx-coroutines-test` at the version matching your
kotlinx-coroutines.

`lostWrites` pins what your code tried to write to a broken stream. A CLI whose standard error fails still writes its
message about the failure there, and the message is lost:

```kotlin
@Test
fun `a full standard error still exits 1 and tries to say why`() = runTest {
    val terminal = RecordingTerminal()
    terminal.failWith(WriteError.Refused(Stream.Err, "No space left on device"))

    assertEquals(1, finish(terminal))
    assertEquals("", terminal.err)
    assertEquals(
        listOf(Write(Stream.Err, "error: cannot write to standard error: No space left on device\n")),
        terminal.lostWrites,
    )
}
```

Each `Write` in `lostWrites` is in the order it was made, and each read is a copy, as with `writes`.

### Details

- `writeResult()` reports standard output's failure before standard error's, as a real terminal does.
- A second `failWith` for the same stream replaces the first.
- `afterWrites` runs its block at once on every target, Node included, since a `RecordingTerminal` has no writes to
  wait for.
- A call to `afterWrites` from inside a block runs once that block returns, as on the real terminal.

## Feeding standard input

`input` is what `readIn()` reads, byte for byte, the first time. A second read finds nothing more, as with a pipe:

```kotlin
@Test
fun `the piped name is greeted`() {
    val terminal = RecordingTerminal(input = "kern\n".encodeToByteArray())

    greet(terminal)

    assertEquals("hello, kern\n", terminal.out)
}
```

- `greet` here is the code under test: it reads standard input and greets the name it holds.
- `readIn(maxBytes)` on an input longer than `maxBytes` is `ReadError.TooLarge` with one byte past the limit as
  `atLeastBytes`, as a real read answers, and leaves the input unread.
- `failWith(ReadError)` fails every later `readIn()` with that error, as `failWith(WriteError)` fails a stream:

```kotlin
@Test
fun `a directory as standard input is reported`() {
    val terminal = RecordingTerminal()
    terminal.failWith(ReadError.IsADirectory)

    greet(terminal)

    assertEquals("error: standard input is a directory\n", terminal.err)
}
```

## License

[Apache-2.0](../LICENSE).
