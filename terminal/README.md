# terminal

Terminal IO for Kotlin Multiplatform: where text goes, how wide it is, and whether it may be coloured.

Writing to a console is the easy part. The hard part is knowing whether the other end is a terminal at all, what
the user has said about colour, and what Windows needs before it renders an escape sequence. This module answers
that once, the same way, on every target.

**Output only.** Reading stdin, asking whether stdin is a pipe, exiting the process and looking up environment
variables are all outside it. A program that needs those keeps its own `expect` declarations for them.
`outIsTerminal` and `errIsTerminal` say whether each *output* stream is a terminal. They come from the same probe
that decides its colour.

**Targets:** JVM, Android, js, wasmJs, wasmWasi, linuxX64, linuxArm64, mingwX64, macosArm64, iosArm64,
iosSimulatorArm64, androidNativeArm64, androidNativeX64, androidNativeArm32, androidNativeX86.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:terminal:$kernVersion")
    testImplementation("com.fromwau.kern:terminal-test:$kernVersion")  // RecordingTerminal, for your tests
}
```

- KMP consumers put it in `commonMain`, and `terminal-test` in `commonTest`.
- Everything is in the package `com.fromwau.kern.terminal`. The snippets below start from
  `import com.fromwau.kern.terminal.*`.
- Needs what [every kern module needs](../README.md#requirements).

## Writing

```kotlin
import com.fromwau.kern.terminal.*

val terminal = defaultTerminal()

terminal.out("hello\n")
terminal.err("something went wrong\n")
```

`defaultTerminal()` is the real process stdout and stderr, with the colour and width policy already applied.

Ask for it once and keep it. In a Windows native (mingwX64) build the first call opts the console into
virtual-terminal processing and UTF-8 output. Both have to happen before anything is written. The JVM on Windows
does neither.

Each `out` and `err` call is one write to the stream, with no buffering in between. When you write many small
pieces and speed matters, join them into one string first.

## Colour

Colour helps a person read output, and garbles a log file or a pipe. Style your text once. The terminal decides per
stream whether the codes go out.

```kotlin
terminal.err(red("error: ", bold(path), " is missing\n"))
```

Invoking a style gives `Styled` text. It turns into escape codes only when a `Terminal` writes it, and only when that
stream takes colour:

- `terminal.out` asks `terminal.ansi`, and `terminal.err` asks `terminal.errAnsi`.
- A piped run prints plain text on its own.
- `2>errors.log` stays plain while standard output is a terminal.

`out(Styled)` and `err(Styled)` are extension functions. If you import names one by one instead of
`com.fromwau.kern.terminal.*`, also import `com.fromwau.kern.terminal.out` and `com.fromwau.kern.terminal.err`.
Without them the call fails with "actual type is 'Styled', but 'String' was expected".

A `Style` is called like a function: `red("x")` gives `Styled`. A `Style.invoke(String)` extension of your own is
shadowed by this member, since a member wins. Rename it, or build `Styled` text and let a `Terminal` render it.

### The palette

- Colours: `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `brightBlack`.
- Styles: `bold`, `dim`, `italic` and `underline`.
- `plain`, which adds nothing.

Combine styles with `+`: `(bold + red)("failed")`. Two colours in one style write both codes in number order. The
terminal shows the last, so `red + green` and `green + red` both show green.

- A style takes any mix of strings and `Styled` pieces.
- Text after a nested piece is written in the outer style again. An empty piece writes nothing.
- Join pieces with `+`: `red("a") + " b"` is `Styled`.
- Join a list with `joinToStyled`: `listOf(red("a"), "b").joinToStyled(dim(" | "))` is `Styled`. `joinToString`
  gives the plain text; use `joinToStyled` to keep the styles.
- `toString()` and a string template give the plain text, never escape codes.
- To hand styled text to something that is not a `Terminal`, `render(ansi)` gives the string with or without codes.

### Overriding the decision

For a `--color=always|never` option, wrap the terminal:

```kotlin
val terminal = when (colorOption) {
    "always" -> defaultTerminal().withAnsi(out = true, err = true)
    "never" -> defaultTerminal().withAnsi(out = false, err = false)
    else -> defaultTerminal()
}
```

`withAnsi` changes `ansi` and `errAnsi` only. Writes, width and failures still come from the terminal it wraps.

### What `ansi` actually decides

The rules, highest precedence first:

| rule | effect |
|---|---|
| `NO_COLOR` set and non-empty | off, always ([no-color.org](https://no-color.org)) |
| `FORCE_COLOR` / `CLICOLOR_FORCE` set, not empty, not `0` and not `false` | on, even when piped |
| handle cannot render escapes | off |
| `CLICOLOR=0` | off |
| `TERM=dumb` | off |
| otherwise | on when that stream is a real tty |

- Any other value of `FORCE_COLOR` or `CLICOLOR_FORCE` forces, `yes` included.
- Forcing is the one rule that ignores what the handle can render. That is what makes `FORCE_COLOR=1` work for CI
  logs that are captured rather than displayed.
- `ansi` answers for standard output and `errAnsi` for standard error, each from its own stream. That is what
  `ls --color=auto` and git do: a piped stdout loses colour while a stderr still on the terminal keeps it.

**Resolving the environment yourself?** The default terminal uses these two functions. You can call them with your
own lookup, such as `{ System.getenv(it) }` or `myEnv::get`:

```kotlin
fun ansiEnabled(isTty: Boolean, env: (String) -> String?, supported: Boolean = true): Boolean
fun resolveColumns(env: (String) -> String?, detected: Int? = null): Int
```

### Sharp edges of colour

- **A `String` on the left wins.** `"a " + red("b")` is a plain `String`. Start with the styled piece, or write
  `plain("a ", red("b"))`.
- **Each line is closed on its own.** The reset goes before every line break, and the next line opens its styles
  again, so `app --color=always | head -1` leaves no colour behind. Line breaks alone are written without codes.
- **Padding counts characters, not columns.** `length`, `padEnd` and `padStart` count the text without the codes a
  style adds: `red(name).padEnd(12)`. They count characters as Kotlin's `String.length` does, so columns line up
  for text whose every character takes one column.
  - A CJK character counts one but takes two columns. A combining accent counts one but takes none. Emoji vary, so
    such text does not line up.
  - A line break counts as one character too, so `red("a\n").padEnd(4)` puts the padding after the break. Pad
    first, then add the newline.
- **Your own escape codes.** An escape code you put inside a `String` piece yourself is written unchanged. That is
  the one way to write a code the palette does not have. `length` and padding count its characters like any other
  text.
- **The JVM and Android cannot ask about one stream.** The JDK's console probe is true only when stdin and stdout
  are both terminals, so `app < input.txt` prints plain text on the JVM though stdout is a terminal. `errAnsi` is
  on there only when forced. Set `FORCE_COLOR=1` where that matters.
- **How the other targets ask.** Natively each stream is asked with `isatty`. On Windows each handle also has to
  accept virtual-terminal processing. Under Node it is `isTTY`, and under WASI the host's file descriptor.
- **A browser console is never coloured.** It shows escape codes as text. A page has no environment, so not even
  `FORCE_COLOR` turns colour on there.
  - `out` goes to `console.log` and `err` to `console.error`. Each call is one console line, so the newline that
    ends your text is dropped.
  - Under Node both write to the process streams.

## Width

`terminal.columns` is the usable width. It is the `COLUMNS` environment variable if set, else the detected terminal
size, else `80`.

How the size is detected:

- Linux and Apple: `ioctl(TIOCGWINSZ)`.
- Windows: `GetConsoleScreenBufferInfo`.
- Node: the stream's `columns`.
- Each of these falls back to stderr when stdout is redirected.
- The JVM, Android, androidNative and WASI cannot detect it. There only `COLUMNS` sets it.
- A browser has neither a terminal nor an environment. There the width is always `80`.

Because of that fallback, a piped stdout still reports stderr's width, so `columns` is no evidence that stdout is a
terminal. To wrap only for a person reading, ask `outIsTerminal`:

```kotlin
val width = if (terminal.outIsTerminal) terminal.columns else 0  // 0: do not wrap
```

`outIsTerminal` comes from the probe that decides `ansi`, and `errIsTerminal` from the one that decides `errAnsi`.
On the JVM and Android `outIsTerminal` is false whenever stdin is redirected, and `errIsTerminal` is always false.

### Details

`resolveColumns` reads `COLUMNS` first, then `detected`:

- It takes `COLUMNS` when it is a whole number above 0 with no spaces around it, so `" 100 "`, `"0"`, `"-5"` and a
  number too large for an `Int` are ignored.
- A leading `+` is allowed: `COLUMNS=+100` gives 100.
- Then it takes `detected` when that is above 0. Otherwise it answers `80`.

## Failed writes

`yourtool | head -1` closes the pipe while you are still writing. `yourtool > /dev/full` runs out of space. Neither
throws, so a program that never asks can exit 0 having written nothing. `afterWrites` asks:

```kotlin
import com.fromwau.kern.result.EmptyResult
import com.fromwau.kern.result.Result
import com.fromwau.kern.terminal.*

fun main() {
    val terminal = defaultTerminal()
    terminal.out("done\n")
    terminal.afterWrites { written -> exit(exitCode(terminal, written)) }
}

fun exitCode(terminal: Terminal, written: EmptyResult<WriteError>): Int =
    when (written) {
        is Result.Success -> 0
        is Result.Error -> when (val error = written.error) {
            is WriteError.BrokenPipe -> BROKEN_PIPE_EXIT
            is WriteError.Refused -> cannotWrite(terminal, error.stream, error.detail)
            is WriteError.Unknown -> cannotWrite(terminal, error.stream, error.detail)
        }
    }

fun cannotWrite(terminal: Terminal, stream: Stream, detail: String?): Int {
    val name = if (stream == Stream.Out) "standard output" else "standard error"
    terminal.err("error: cannot write to $name: $detail\n")
    return 1
}
```

- `BROKEN_PIPE_EXIT` is the shell's 128+SIGPIPE convention for reporting a pipe the reader closed.
- Import kern's `Result`. Without it, `Result` is Kotlin's own and the `when` does not compile.

`exit` is yours, since ending the process is outside this module:

```kotlin
// commonMain
expect fun exit(code: Int): Nothing

// jvmMain, androidMain and nativeMain
actual fun exit(code: Int): Nothing = exitProcess(code)  // import kotlin.system.exitProcess

// jsMain and wasmJsMain, or webMain for both: under Node (import kotlin.js.ExperimentalWasmJsInterop)
actual fun exit(code: Int): Nothing {
    processExit(code)
    error("process.exit returned")
}

@OptIn(ExperimentalWasmJsInterop::class)
private fun processExit(code: Int): Unit = js("process.exit(code)")

// wasmWasiMain (import kotlin.wasm.ExperimentalWasmInterop and kotlin.wasm.WasmImport)
actual fun exit(code: Int): Nothing {
    procExit(code)
    error("proc_exit returned")
}

@OptIn(ExperimentalWasmInterop::class)
@WasmImport("wasi_snapshot_preview1", "proc_exit")
private external fun procExit(code: Int)
```

Under WASI the host decides what becomes of the code. The Node launcher Kotlin generates for a WASI executable
ignores it, so the process exits 0 there.

### When the block runs

`afterWrites` calls your block once, with `writeResult()`, after every write so far has landed or failed.

- Only Node needs the wait. It reports a failed write after the write returns, so `writeResult()` right after
  writing answers success there.
- Under Node the block runs later. The pending writes keep the process alive until it does, so the block can end
  the process with nothing left to write.
- Everywhere else it runs at once. The exception is a call to `afterWrites` from inside another block, as
  [Writing a long output](#writing-a-long-output) describes.

Make `afterWrites` your last call. Code after it runs after the block on the JVM, Android, natively and under WASI,
but before the block under Node.

Code that already suspends can ask `awaitWriteResult()` instead. It waits the same way:

```kotlin
suspend fun finish(terminal: Terminal): Int = exitCode(terminal, terminal.awaitWriteResult())
```

### Which stream failed

`writeResult()`, `afterWrites` and `awaitWriteResult()` report standard output's failure before standard error's.
Each error names its `stream`. A stream writes nothing after its first failure.

To ask about one stream alone, call `writeResult(Stream.Out)` or `writeResult(Stream.Err)`. On `defaultTerminal()`
and `RecordingTerminal`, a failure on the other stream neither hides it nor stands in for it. A terminal of your own
that overrides only `writeResult()` reports at most the stream that one names.

### What each platform reports

| failure | Linux and macOS | WASI | JVM and Android | Node |
|---|---|---|---|---|
| the reader closed the pipe | `SIGPIPE` ends the process | `BrokenPipe` | `Unknown` | `BrokenPipe` |
| a full disk | `Refused` | `Refused` | `Unknown` | `Unknown` |
| a closed handle (`>&-`, `2>&-`) | `Refused` | `Refused` | `Unknown` | success |

- **Linux and macOS.** A closed pipe ends the process with `SIGPIPE` first, or is `BrokenPipe` if you ignore that
  signal. `Refused` carries the system's message.
- **WASI.** `Refused` carries the WASI error number.
- **JVM and Android.** `Unknown` carries the JDK's message.
- **Node.** Failures arrive from `afterWrites`. `Unknown` carries Node's message. A closed handle is a success,
  because Node opens `/dev/null` in its place at startup, for standard output and standard error alike.

The JVM raises every failed write as the same exception, without the system's error code, so it cannot tell a
closed pipe from a full disk. `yourtool | head -1` reaches the `Unknown` branch there. That is why the snippet above
prints the failure and exits 1 rather than guessing.

On the JVM, `detail` is the JDK's message, in the system's language. `cause` is the `IOException` the JDK threw,
for you to inspect or log. Elsewhere `cause` is null.

### Sharp edges of failed writes

- **Node keeps running** after a closed pipe.
- **Your last message under Node.** On Linux, a message the block writes, like `cannotWrite`'s, still reaches
  standard error before the block ends the process. That holds also when standard error is a pipe, since Node
  hands it to the pipe at once. Only what the pipe cannot take at once is lost, so a message longer than the pipe's
  64 KiB can be cut short. Other systems are untested.
- **A browser console write** cannot fail.
- **What is untested.** Under WASI only a write the host refuses outright is tested. The closed pipe and full disk
  answers are untested. On Windows the answer follows the C runtime's error code, which is untested.

### Writing a long output

A tool that prints millions of lines should not write them all into a closed pipe. `outEach` writes a sequence of
pieces, each as given, and stops soon after the reader goes away:

```kotlin
fun main() {
    val terminal = defaultTerminal()
    val lines = (1..10_000_000).asSequence().map { "line $it\n" }
    terminal.outEach(lines) { written -> exit(exitCode(terminal, written)) }
}
```

- It joins the pieces into writes of about 64 K characters. A longer piece is written alone, never split.
- After each write it waits as `afterWrites` does.
- It takes no more pieces once a write to standard output has failed.
- A failed standard error does not stop the output. It is reported at the end.

Your block gets what `afterWrites` would pass: success, or standard output's failure before standard error's. On
the JVM a closed pipe arrives as `Unknown`, so `exitCode` prints the error and exits 1.

Code that suspends can write a `Flow` the same way, with `outEach` from
[`kern:terminal-coroutines`](../terminal-coroutines/README.md).

#### Details

- A call to `afterWrites` from inside a block does not run inside it. Its block runs once the running one returns.
- So a loop that writes and calls `afterWrites` again from its block keeps a flat stack however long it runs. That
  is how `outEach` works.
- This holds per thread, for every terminal. Inside a block, another terminal's `afterWrites` waits too.
- Waiting blocks run in the order they were added.
- If a block throws, the exception reaches the outermost `afterWrites` call. Under Node it is uncaught. The blocks
  still waiting are dropped.

### Writing your last message after a closed pipe

On Linux and macOS a write to a closed pipe ends the process with `SIGPIPE` before `writeResult()` can answer. That
is usually what you want: `yourtool | head -1` stops quietly. kern does not change it.

Ignore the signal yourself when you still need to act after the pipe closes, for example to write a last message to
stderr or choose your own exit code:

```kotlin
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.SIGPIPE
import platform.posix.SIG_IGN
import platform.posix.signal

@OptIn(ExperimentalForeignApi::class)
fun ignoreBrokenPipes() {
    signal(SIGPIPE, SIG_IGN)
}
```

- Call it first thing in `main`.
- Windows has no `SIGPIPE`, so with a mingw target this goes in `linuxMain`, `appleMain` or `androidNativeMain`
  rather than `nativeMain`.
- Writes to the closed pipe then fail as `BrokenPipe`.

## Testing

`RecordingTerminal`, from `kern:terminal-test`, keeps what each stream was sent. It fails a stream's writes when you
tell it to, with the error you build:

```kotlin
import com.fromwau.kern.terminal.test.RecordingTerminal

val terminal = RecordingTerminal()
terminal.failWith(WriteError.BrokenPipe(Stream.Out))
terminal.failWith(WriteError.Refused(Stream.Err, "disk full"))
```

`WriteError.Unknown(Stream.Out, "No space left on device")` builds the third kind. Its `cause` is optional, since it
is null outside the JVM and Android. See the [terminal-test README](../terminal-test/README.md).

`Terminal` is an interface, so a terminal of your own works too:

```kotlin
class Recorder : Terminal {
    val written = StringBuilder()
    override fun out(text: String) { written.append(text) }
    override fun err(text: String) = Unit
}
```

One that overrides only `out` and `err` has these defaults:

- `columns` is `0`.
- `ansi`, `errAnsi`, `outIsTerminal` and `errIsTerminal` are false.
- Every write succeeds.

So there is no wrapping and there are no escape codes. To report failures, override `writeResult()`.
`writeResult(stream)`, `afterWrites` and `awaitWriteResult()` read it, so overriding only `writeResult(stream)`
leaves `writeResult()` answering success.

## License

[Apache-2.0](../LICENSE).
