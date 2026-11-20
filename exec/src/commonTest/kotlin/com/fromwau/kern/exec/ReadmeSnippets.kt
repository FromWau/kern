package com.fromwau.kern.exec

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.onError
import com.fromwau.kern.result.onSuccess
import kotlinx.io.files.Path

// The README's samples, compiled here so they keep compiling without warnings. Nothing calls them.

private fun gitLog(): Int? {
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

private val app = Command(listOf("firefox"), stdout = Output.Discard, stderr = Output.Discard)
private val build = Command(listOf("make"), stdout = Output.File(Path("build.log")), stderr = Output.ToStdout)

private val counted = Command(
    argv = listOf("/bin/sh", "-c", "sort | uniq -c"),
    environment = Environment.InheritWith(set = mapOf("LC_ALL" to "C")),
    workingDirectory = Path("/tmp"),
    stdin = Stdin.Bytes("b\na\nb\n".encodeToByteArray()),
)

private fun interrupt(child: Child) {
    child.stop(Signal.Interrupt)
        .onError { println("could not stop ${child.pid}: $it") }
}

private fun openInBrowser(url: String) {
    SystemStarter.start(Command(listOf("xdg-open", url)))
        .onSuccess { it.detach() }
        .onError { println("cannot open $url: $it") }
}

private class Runner(private val starter: Starter = SystemStarter) {
    fun run(line: String) = starter.start(Command(listOf("/bin/sh", "-c", line)))
}

private val withoutShell = Runner { command -> Err(ExecError.ProgramNotFound(command.argv.first())) }
