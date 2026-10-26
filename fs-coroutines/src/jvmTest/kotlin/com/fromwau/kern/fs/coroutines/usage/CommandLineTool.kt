package com.fromwau.kern.fs.coroutines.usage

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.current
import com.fromwau.kern.fs.coroutines.awaitLock
import com.fromwau.kern.fs.div
import com.fromwau.kern.result.*
import kotlinx.coroutines.runBlocking

// The README's command-line tool, as written there; CommandLineToolJvmTest runs it as its own process.
fun main() = runBlocking<Unit> {
    val dirs: AppDirs = BaseDirs.current()
        .flatMap { it.forApp("mytool") }
        .getOrElse { failure -> error("no place for mytool's files: $failure") }
    (dirs.state / ".sync.lock").awaitLock { syncNow() }.onError { println("not synced: $it") }
}

private fun syncNow() = println("synced")
