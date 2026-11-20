package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.Child
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.ExecError
import com.fromwau.kern.exec.Signal
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.getOrNull
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

// The README's samples, compiled here so they keep compiling without warnings. Nothing calls them.

private suspend fun printUntilReady(child: Child) {
    child.events()
        .takeWhile { event -> !(event is ChildEvent.Output && "ready" in event.text) }
        .collect { event -> if (event is ChildEvent.Output) print(event.text) }
}

private suspend fun exitCode(child: Child): Int? = when (val exit: Result<Int, ExecError> = child.awaitExit()) {
    is Result.Success -> exit.value
    is Result.Error -> {
        println("no exit code: ${exit.error}")
        null
    }
}

private suspend fun killAfterTimeout(child: Child) {
    val _ = withTimeoutOrNull(5.seconds) { child.awaitExit() } ?: child.stop(Signal.Kill)
}

private suspend fun currentBranch(): String? {
    val git = Command(listOf("git", "branch", "--show-current"))
    return SystemStarter.run(git, timeout = 5.seconds).getOrNull()?.stdout?.trim()
}
