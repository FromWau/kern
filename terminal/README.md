# terminal

Terminal IO for Kotlin Multiplatform: where text goes, how wide it is, and whether it may be coloured.

The awkward part of writing to a console is not writing. It is knowing whether the thing on the other end
is a terminal at all, what the user has said about colour, and what Windows needs before it will render an
escape sequence. This module answers that once, the same way, on every target.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

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

KMP consumers put it in `commonMain`. The JVM and Android artifacts are Java 25 bytecode, so those two need
a JDK 25 toolchain; the native targets have no such requirement.

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
`terminal.ansi` and a piped run prints plain text on its own.

The palette is `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `brightBlack`, plus
`bold`, `dim`, `italic` and `underline`. Combine with `+`.

**What `ansi` actually decides**, highest precedence first:

| rule | effect |
|---|---|
| `NO_COLOR` set and non-empty | off, always ([no-color.org](https://no-color.org)) |
| `FORCE_COLOR` / `CLICOLOR_FORCE` not `0` | on, even when piped |
| handle cannot render escapes | off |
| `CLICOLOR=0` | off |
| `TERM=dumb` | off |
| otherwise | on when stdout is a real tty |

Forcing is the one rung that ignores what the handle can render, which is what makes `FORCE_COLOR=1` work
for CI logs that are captured rather than displayed.

Resolving the environment yourself instead? `ansiEnabled(isTty, env, supported)` is the same function the
default terminal uses.

## Width

`terminal.columns` is the usable width: the `COLUMNS` environment variable if set, else the detected
terminal size, else `80`. Detection is `ioctl(TIOCGWINSZ)` on POSIX and `GetConsoleScreenBufferInfo` on
Windows, each falling back to stderr when stdout is redirected.

`0` is never returned by `defaultTerminal()`, but a hand-written `Terminal` may return it to mean "unknown,
do not wrap".

## Broken pipes

```kotlin
terminal.out(everything)
if (terminal.writeErrored()) return BROKEN_PIPE_EXIT
```

`yourtool | head -1` closes the pipe while you are still writing. On the JVM that surfaces as a latched
error flag rather than an exception, so a program that never asks reports success having written nothing.
`writeErrored()` asks, and `BROKEN_PIPE_EXIT` is the shell's 128+SIGPIPE convention for reporting it.

POSIX native needs none of this: `SIGPIPE` ends the process before anything can ask, which is why
`writeErrored()` is always `false` there.

## License

[Apache-2.0](../LICENSE).
