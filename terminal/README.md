# terminal

Terminal IO for Kotlin Multiplatform: where text goes, how wide it is, and whether it may be coloured.

The awkward part of writing to a console is not writing. It is knowing whether the thing on the other end
is a terminal at all, what the user has said about colour, and what Windows needs before it will render an
escape sequence. This module answers that once, the same way, on every target.

**Output only.** Reading stdin, asking whether stdin is a pipe, exiting the process and looking up
environment variables are all outside it, so a program that needs those still keeps its own `expect`
declarations for them. `ansi` and `errAnsi` are decided partly from whether that *output* handle is a
terminal, and neither is exposed as a general tty probe.

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
}
```

KMP consumers put it in `commonMain`. Needs what [every kern module needs](../README.md#requirements).

## Writing

```kotlin
val terminal = defaultTerminal()

terminal.out("hello\n")
terminal.err("something went wrong\n")
```

`defaultTerminal()` is the real process stdout/stderr with the colour and width policy already applied. Ask
for it once and keep it: on Windows the first call opts the console into virtual-terminal processing and
UTF-8 output, and both have to happen before anything is written.

`Terminal` is an interface, so a test substitutes its own without touching the process streams:

```kotlin
class Recorder : Terminal {
    val written = StringBuilder()
    override fun out(text: String) { written.append(text) }
    override fun err(text: String) = Unit
}
```

## Colour

```kotlin
val terminal = defaultTerminal()

println((bold + red).render("failed", terminal.ansi))
```

`render` takes the decision rather than making it, so nothing in your code branches on colour. Pass
`terminal.ansi` and a piped run prints plain text on its own. Text you write with `terminal.err` takes
`terminal.errAnsi` instead, so `2>errors.log` stays plain while standard output is a terminal.

The palette is `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `brightBlack`, plus
`bold`, `dim`, `italic` and `underline`. Combine with `+`. A styled piece may sit inside another, and the text
after it keeps the outer style.

**What `ansi` actually decides**, highest precedence first:

| rule | effect |
|---|---|
| `NO_COLOR` set and non-empty | off, always ([no-color.org](https://no-color.org)) |
| `FORCE_COLOR` / `CLICOLOR_FORCE` set, not empty and not `0` | on, even when piped |
| handle cannot render escapes | off |
| `CLICOLOR=0` | off |
| `TERM=dumb` | off |
| otherwise | on when that stream is a real tty |

`ansi` answers for standard output and `errAnsi` for standard error, each from its own stream, as
`ls --color=auto` and git do: a piped stdout loses colour while a stderr still on the terminal keeps it.

**The JVM cannot ask about one stream.** Its only probe, `System.console().isTerminal()`, is true only when
stdin *and* stdout are both terminals, so `app < input.txt` prints plain text on the JVM though stdout is a
terminal, and `errAnsi` is on only when forced. Set `FORCE_COLOR=1` where that matters. Natively each stream
is asked with `isatty`, under Node with `isTTY`, and under WASI from the host's file descriptor.

**A browser console is never coloured.** It shows escape codes as text, and a page has no environment, so not
even `FORCE_COLOR` turns colour on there. `out` goes to `console.log` and `err` to `console.error`, each call one
console line, so the newline that ends your text is dropped. Under Node both write to the process streams.

Forcing is the one rung that ignores what the handle can render, which is what makes `FORCE_COLOR=1` work
for CI logs that are captured rather than displayed.

Resolving the environment yourself instead? `ansiEnabled(isTty, env, supported)` is the same function the
default terminal uses.

## Width

`terminal.columns` is the usable width: the `COLUMNS` environment variable if set, else the detected
terminal size, else `80`. Detection is `ioctl(TIOCGWINSZ)` on Linux and Apple, `GetConsoleScreenBufferInfo` on
Windows and the stream's `columns` under Node, each falling back to stderr when stdout is redirected. The JVM,
Android, androidNative and WASI cannot detect it, so there only `COLUMNS` sets it. A browser has neither a
terminal nor an environment, so there the width is always `80`.

`0` is never returned by `defaultTerminal()`, but a hand-written `Terminal` may return it to mean "unknown,
do not wrap".

## Failed writes

```kotlin
terminal.out(everything)
when (val written = terminal.writeResult()) {
    is Result.Success -> return 0
    is Result.Error -> when (val error = written.error) {
        is WriteError.BrokenPipe, is WriteError.Unknown -> return BROKEN_PIPE_EXIT
        is WriteError.Refused -> {
            val name = if (error.stream == Stream.Out) "standard output" else "standard error"
            terminal.err("error: cannot write to $name\n")
            return 1
        }
    }
}
```

`yourtool | head -1` closes the pipe while you are still writing, and `yourtool > /dev/full` runs out of
space. Neither throws, so a program that never asks reports success having written nothing. `writeResult()`
asks, and `BROKEN_PIPE_EXIT` is the shell's 128+SIGPIPE convention for reporting a pipe the reader closed.

`writeResult()` reports standard output's failure before standard error's. To ask about one stream alone, call
`writeResult(Stream.Out)` or `writeResult(Stream.Err)`: a failure on the other stream neither hides it nor stands
in for it.

| failure | Linux and macOS | WASI | JVM and Android | Node |
|---|---|---|---|---|
| the reader closed the pipe | the process ends with `SIGPIPE` first, or `BrokenPipe` if you ignore that signal | `BrokenPipe` | `Unknown` | `BrokenPipe`, once Node reports it |
| a full disk, a closed handle | `Refused`, with the system's message | `Refused`, with the WASI error number | `Unknown`, with the JDK's message | `Unknown` |

The JVM raises every failed write as the same exception, without the system's error code, so it cannot
tell a closed pipe from a full disk. Node reports a closed pipe as an error on the stream just after the write
returns. The process keeps running, and `writeResult()` answers `BrokenPipe` for that stream from then on. A
browser console write cannot fail. On Windows the answer follows the C runtime's error code, which is untested.
Each error names its `stream`. A stream writes nothing after its first failure.

## License

[Apache-2.0](../LICENSE).
