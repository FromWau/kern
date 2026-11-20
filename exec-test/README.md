# exec-test

A unit test should not start real programs. This module gives you a fake instead. `FakeStarter` is a test `Starter`
for [`kern:exec`](../exec/README.md): you script what each command does, and it records what was started.

- It starts no process. Each command gets the scripted events or the scripted failure.
- It records every command and every child it made.
- Nothing blocks: a child plays its events, then `next()` returns null.

**Targets:** the same as [`kern:exec`](../exec/README.md).

## Add to your build

```kotlin
dependencies {
    testImplementation("com.fromwau.kern:exec-test:$kernVersion")
}
```

- KMP consumers put it in `commonTest`.
- `kern:exec` comes along as `api`.
- `FakeStarter`, `FakeRun` and `FakeChild` are in the package `com.fromwau.kern.exec.test`.
- Needs what [every kern module needs](../README.md#requirements), with JDK 22 or newer on the JVM.

## Scripting a command

Write the code under test against `Starter`. Pass it `SystemStarter` in the program and a `FakeStarter` in the test:

```kotlin
import com.fromwau.kern.exec.*
import com.fromwau.kern.exec.test.*
import com.fromwau.kern.result.Result
import kotlin.test.*

fun gitBranch(starter: Starter): String? {
    val child = (starter.start(Command(listOf("git", "branch", "--show-current"))) as? Result.Success)?.value
        ?: return null
    val text = StringBuilder()
    while (true) {
        when (val event = child.next() ?: return text.toString().trim()) {
            is ChildEvent.Output -> text.append(event.text)
            else -> Unit
        }
    }
}

@Test
fun `the branch is what git wrote`() {
    val starter = FakeStarter {
        FakeRun.Starts(
            listOf(
                ChildEvent.Output("main\n", ChildStream.Stdout),
                ChildEvent.Exited(0),
                ChildEvent.OutputClosed,
            ),
        )
    }

    assertEquals("main", gitBranch(starter))
    assertEquals(listOf("git", "branch", "--show-current"), starter.started.single().argv)
}
```

`FakeStarter` takes a function from the `Command` to its `FakeRun`, so one starter can answer each command its own way.
A command your function does not know is yours to fail: return `FakeRun.Fails(ExecError.ProgramNotFound(name))`.

| `FakeRun` | what the start does |
|---|---|
| `Starts(events, pid = 4242)` | succeeds with a child that plays `events` in order |
| `Fails(error)` | returns `error` and makes no child |
| `Live(pid = 4242)` | succeeds with a child that plays what the test hands it with `FakeChild.emit` |

| member | what it gives |
|---|---|
| `FakeStarter.started` | every command passed to `start`, in order, failed starts included |
| `FakeStarter.children` | the children made, in order |
| `FakeChild.stops` | the signals `stop` was called with |
| `FakeChild.detached` | whether `detach` was called |
| `FakeChild.emit(events)` | adds events after those not yet taken, as output written now |

A `FakeChild` can also be made without a starter, for code that takes a `Child`: `FakeChild(events)`, or
`FakeChild(live = true)` for one the test feeds.

## Feeding a child while it runs

A live child models output that arrives after something the test checks. Its `next()` waits until the test emits
something, stops it or detaches it. `FakeChild` is a `SuspendingChild` from
[`exec-coroutines`](../exec-coroutines/README.md), so `events()` and `awaitExit()` suspend on it instead of blocking,
and `runTest` drives them in virtual time:

```kotlin
@Test
fun `each line shows as it comes`() = runTest {
    val child = FakeChild(live = true)
    val seen = mutableListOf<ChildEvent>()
    val reading = launch { child.events().collect { seen += it } }

    child.emit(ChildEvent.Output("building\n", ChildStream.Stdout))
    runCurrent()
    assertEquals(listOf<ChildEvent>(ChildEvent.Output("building\n", ChildStream.Stdout)), seen)

    child.emit(ChildEvent.Exited(0), ChildEvent.OutputClosed)
    reading.join()
}
```

Its end follows the same rule as a scripted child's: after `Exited` and `OutputClosed` were both taken, or after
`Failed`, and `emit` adds nothing from then on. Code that calls `next()` itself blocks its thread while it waits, so
read it that way only on a thread of its own.

## How a fake child behaves

The fake follows the rules of a real `Child`:

- `next()` returns the scripted events in order, then null. After both `Exited` and `OutputClosed` were returned, or
  after `Failed`, it returns null even when more events were scripted.
- `stop(signal)` returns success. The output not yet taken still comes, as a real child's output written before the
  signal does. A scripted `Exited` or `OutputClosed` not yet taken is dropped, and `Exited(128 + signal.number)` then
  `OutputClosed` come after the output. Events already taken are not played again, so a stop after `Exited` plays
  only `OutputClosed`.
- A second `stop` is recorded and plays nothing more. A `stop` after `Failed` is the same.
- After `detach()`, `next()` returns null. `stop` is still recorded.

A script that ends without `Exited` or `OutputClosed` just ends: the fake does not add them.

## Sharp edges

**The script is not checked.** The fake plays what you wrote, even an `Exited` before the output that a real child
would have written first. Script the order your code must handle.

**A scripted child does not wait.** Its `next()` never blocks, so a test of code that waits for a slow child sees the
events at once; use a live child to space them out. With
[`exec-coroutines`](../exec-coroutines/README.md), `events()` over a fake child completes right after its last
scripted event. A `FakeStarter` and its children can be used from several threads, such as a reader thread and a test
that detaches.

## License

[Apache-2.0](../LICENSE).
