# exec

Run a program from Kotlin, a native Linux binary as much as a JVM app: `git log`, `ls -lah` or a whole shell line.
`exec` starts it with the argument list you give, hands you what it writes to stdout and stderr as it writes it, tells
you how it ended, and stops it or lets go of it when you decide to. Nothing is stopped behind your back: not when you
drop the child, not when this process exits.

**Targets:** JVM (Java 22 and later, on x86_64 and aarch64 Linux), linuxX64, linuxArm64.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:exec:$kernVersion")
}
```

- KMP consumers put it in `commonMain`.
- Everything is in the package `com.fromwau.kern.exec`.
- kern's `result` and kotlinx-io come along as `api`. `Result` and `Path` appear in its signatures.
- Needs what [every kern module needs](../README.md#requirements), with JDK 22 or newer on the JVM.

## Running a command

`SystemStarter.start` returns once the program runs, with a `Child`, or with the reason it did not start. Then
`next()` hands you one event at a time until there are none left:

```kotlin
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.Result

fun gitLog(): Int? {
    val child = when (val started = SystemStarter.start(Command(listOf("git", "log", "--oneline")))) {
        is Result.Success -> started.value
        is Result.Error -> {
            println("git did not start: ${started.error}")
            return null
        }
    }
    var code: Int? = null
    while (true) {
        when (val event = child.next() ?: break) {
            is ChildEvent.Output -> print(event.text)
            is ChildEvent.Exited -> code = event.code
            ChildEvent.OutputClosed -> {}
            is ChildEvent.Failed -> println("lost track of git: ${event.error}")
        }
    }
    return code
}
```

- The argv goes to the program exactly as given. No shell reads it, so spaces, quotes, empty strings and `*` mean
  nothing special.
- A shell line is yours to start: `listOf("/bin/sh", "-c", line)`.
- A program name without a `/` is looked up in the `PATH` the child gets, not in this process's. A name with a `/` is
  used as given, a relative one from the child's working folder.
- `start` never throws. An empty argv is an error like any other.

The rest of a `Command` is the child's environment, working folder and input:

```kotlin
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.Environment
import com.fromwau.kern.exec.Stdin
import kotlinx.io.files.Path

val counted = Command(
    argv = listOf("/bin/sh", "-c", "sort | uniq -c"),
    environment = Environment.InheritWith(set = mapOf("LC_ALL" to "C")),
    workingDirectory = Path("/tmp"),
    stdin = Stdin.Bytes("b\na\nb\n".encodeToByteArray()),
)
```

| `Environment` | the child sees |
|---|---|
| `Inherit` (the default) | this process's variables |
| `InheritWith(set, unset)` | this process's variables with `set` added or replaced, then `unset` removed |
| `Exactly(variables)` | only `variables`; without a `PATH` in them, a bare program name is not found |

- `workingDirectory` null (the default) is this process's own.
- `Stdin.Empty` (the default) gives the child the end of its input at once. `Stdin.Bytes` gives it exactly those bytes,
  then the end. A child that exits without reading them all is not an error.
- `Stdin.Bytes` does not copy the array. Leave it unchanged until the child has exited.

`stdout` and `stderr` say where the child's output goes. Only `Output.Pipe`, the default, reaches `next()`:

```kotlin
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.Output
import kotlinx.io.files.Path

val app = Command(listOf("firefox"), stdout = Output.Discard, stderr = Output.Discard)
val build = Command(listOf("make"), stdout = Output.File(Path("build.log")), stderr = Output.ToStdout)
```

| `Output` | the stream goes |
|---|---|
| `Pipe` (the default) | to this process, read through `next()` |
| `Discard` | to `/dev/null` |
| `Inherit` | wherever this process's own stdout or stderr goes, such as its terminal |
| `File(path, append = false)` | into the file, created with mode 0666 less the umask; emptied first unless `append`; a relative path from the child's working folder |
| `ToStdout` | stderr only: wherever stdout goes, as `2>&1`; stderr over a piped stdout comes as `Stdout` output |

- With neither stream piped, `OutputClosed` comes at once and `Exited` when the child ends.
- A file that cannot be opened is `OutputFileFailed` and starts nothing. The file is opened just before the start, so
  a start failing after it leaves the file there, empty, as a shell does.

## Reading events

| event | means |
|---|---|
| `Output(text, stream)` | a piece of what the child wrote to `ChildStream.Stdout` or `ChildStream.Stderr` |
| `Exited(code)` | the child ended: its exit status, or 128 plus N when signal N killed it, as a shell reports it |
| `OutputClosed` | nothing writes the child's stdout or stderr any more |
| `Failed(error)` | reading the output, writing `Stdin.Bytes` or waiting for the exit failed; the last event |

- `Output` comes in the order it was written, across both streams.
- `Output` is a slice as the system handed it over, not a line. It is decoded as UTF-8: a character split between two
  slices is held until it is whole. Bytes that are not UTF-8 become `�` (U+FFFD), one for each maximal subpart as the
  Unicode standard recommends, so the same bytes give the same text on every platform however they are split.
- Everything the child wrote before it ended comes before `Exited`.
- `Exited` and `OutputClosed` come once each, in the order they happened. A background job the child left running
  keeps the output open past the exit, and what it writes comes between the two.
- `next()` blocks until there is an event. It returns null once `Exited` and `OutputClosed` both came, after
  `Failed`, and after `detach`.
- The output is read from the moment the child starts, whether you call `next()` or not, so the child never stalls on
  a full pipe. What you have not taken yet is kept, in order, with no limit. A child that writes much and that you
  never read takes that much memory, so `detach` from it instead.
- After `Failed` the child is not stopped. Its output is read and dropped, and the child is reaped once it ended and
  `next()` returned null, or you `detach`.

## Stop and detach

`stop` sends a signal, `Signal.Terminate` (SIGTERM) by default, and returns without waiting. The child's `Exited`
follows through `next()`: 143 for SIGTERM, 137 for SIGKILL.

```kotlin
import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.result.onError

fun interrupt(child: Child) {
    child.stop(Signal.Interrupt)
        .onError { println("could not stop ${child.pid}: $it") }
}
```

- The signals are `Terminate`, `Kill`, `Interrupt`, `Hangup`, `Quit`, and `Number(n)` for any other by its Linux
  number.
- The signal goes to the child's whole process group, so `sh -c "a | b"` stops whole, and so does a background job
  the child left running after it exited.
- `Command(newSession = true)` starts the child as the leader of a session of its own, with no controlling terminal,
  as `setsid` does, so the hangup sent when your terminal closes never reaches it. `stop` still reaches its whole
  group, which a `setsid` wrapper in the argv would hide: there the group is the wrapper's alone.
- A child that already ended is not an error. Once `next()` has returned null after the last event, or after
  `detach` once the child ended, `stop` sends nothing and succeeds.
- `stop` works after `Failed` and after `detach` as before them.

`detach` stops reading the child without stopping it:

```kotlin
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess

fun openInBrowser(url: String) {
    SystemStarter.start(Command(listOf("xdg-open", url)))
        .onSuccess { it.detach() }
        .onError { println("cannot open $url: $it") }
}
```

- From then on `next()` returns null. `stop` still reaches the child, so with
  [`exec-coroutines`](../exec-coroutines/README.md) a wait that timed out can end in a kill:
  `withTimeoutOrNull(5.seconds) { child.awaitExit() } ?: child.stop(Signal.Kill)`.
- The child's output is still read and dropped until it closes, so the child never blocks on a full pipe.
- The child is reaped once it ended and its output closed, so it leaves no zombie behind.

## Platforms

The JVM starts children as Linux native does, through `posix_spawn` called by the JDK's foreign function API, which
is why `exec` needs Java 22 or later where every other kern module needs 17.

- **Grant native access.** Run your app with `--enable-native-access=ALL-UNNAMED`, or put `Enable-Native-Access:
  ALL-UNNAMED` in an executable jar's manifest. Without it the JDK prints a warning the first time a child starts,
  and a JDK that denies native access (`--illegal-native-access=deny`) starts nothing: every `start` is `Unsupported`
  saying so.
- **Only on x86_64 and aarch64 Linux.** A JVM on another system or CPU starts nothing: every `start` is `Unsupported`
  naming what it runs on. The constants and struct layouts the JVM side uses are those of glibc there; it is tested
  on x86_64 glibc.

## Errors

`start` returns `Result<Child, ExecError.StartError>`: only what a start can answer, so a `when` over it lists those
cases alone. `stop` returns `Result<Unit, ExecError>`, and a `Failed` event carries an `ExecError`. Every
`StartError` is an `ExecError` too.

| `StartError` | when |
|---|---|
| `EmptyCommand` | the argv is empty |
| `InvalidCommand(argv, problem)` | the command holds what no process can be given: a NUL character, an environment name that is empty or holds `=`, or `Output.ToStdout` as stdout |
| `ProgramNotFound(program)` | there is no such program, or a bare name is not in the child's `PATH` |
| `NotExecutable(program)` | the program exists but may not be run |
| `InterpreterMissing(program)` | the program exists and may be run, but the interpreter its `#!` line names, or the dynamic loader it needs, is missing |
| `WorkingDirectoryMissing(path)` | the working folder does not exist or is not a folder |
| `OutputFileFailed(path, stream, reason)` | the file `Output.File` names for `stream` could not be opened |
| `Unsupported(reason)` | this JVM cannot start children (see [Platforms](#platforms)), or the C library is too old for what the command asks |
| `StartFailed(argv, reason)` | the system refused, such as at a process or memory limit; `reason` is its message |

| `ExecError` | from | when |
|---|---|---|
| `StopFailed(pid, reason)` | `stop` | the system refused the signal, such as an unknown number |
| `ReadFailed(stream, reason)` | `Failed` | reading stdout or stderr failed |
| `WaitFailed(pid, reason)` | `Failed` | waiting for the child to end failed |
| `StdinFailed(reason)` | `Failed` | writing `Stdin.Bytes` failed for a reason other than the child closing its input |
| `TimedOut(after, stdout, stderr)` | `run` in [`exec-coroutines`](../exec-coroutines/README.md) | the command still ran at the timeout; it was stopped, and both streams hold what they had got |
| `Detached` | `awaitExit` in [`exec-coroutines`](../exec-coroutines/README.md) | no exit is left to read: the child was detached, or its events were read to the end |

`program` is the name as given in the argv.

## Sharp edges

- **A piped child that outlives this process dies when it writes.** Once this process exits, nothing reads the
  child's output, and its next write gets SIGPIPE, which ends most programs. `detach` does not change that. A child
  meant to outlive you writes somewhere else: `stdout = Output.Discard` or `Output.File(...)`, and the same for
  stderr.
- **The child sees pipes, not a terminal.** `ls` prints one name per line, programs drop their colours and their
  progress bars, and many C programs then write their output in blocks of a few KiB instead of line by line.
  `stdbuf -oL` in front of such a program makes it write per line again. Nothing here gives a child a terminal or lets
  you write to its input after it started.
- **Linux before 5.3 reports `Exited` late.** There the exit is waited for only once the output has closed, so a
  background job that keeps it open holds `Exited` back until it closes.
- **On the JVM with glibc before 2.34, the child inherits stray descriptors.** Everywhere else the child gets only its
  stdin, stdout and stderr. There, a file or socket this process opened without close-on-exec stays open in the child,
  so closing it here does not end it until the child exits too.
- **A child leaves a zombie entry until you read it to the end or detach it.** The exited child's pid is held by an
  entry in the process table until `next()` has returned null after the last event, or you `detach`, so that `stop`
  reaches its whole group and never a process that got the same number. A child you start and then neither read to
  the end nor detach keeps that entry, and a thread, until this process exits.

## Testing

`Starter` is a `fun interface` with `SystemStarter` as the real one. Code that takes a `Starter` can be handed a
scripted one in a test:

```kotlin
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Starter
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.Err

class Runner(private val starter: Starter = SystemStarter) {
    fun run(line: String) = starter.start(Command(listOf("/bin/sh", "-c", line)))
}

val withoutShell = Runner { command -> Err(ExecError.ProgramNotFound(command.argv.first())) }
```

[`exec-test`](../exec-test/README.md) has `FakeStarter`, a scripted `Starter` that records what was started.

## License

[Apache-2.0](../LICENSE).
