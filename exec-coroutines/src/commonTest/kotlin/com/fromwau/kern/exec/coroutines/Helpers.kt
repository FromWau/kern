package com.fromwau.kern.exec.coroutines

import com.fromwau.kern.exec.ChildEvent
import com.fromwau.kern.exec.Command
import com.fromwau.kern.exec.ChildStream
import com.fromwau.kern.exec.SystemStarter
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path

/** `/bin/sh -c script`. */
fun sh(script: String) = Command(listOf("/bin/sh", "-c", script))

/** A path in a new empty folder made by `mktemp -d`, where nothing exists yet. */
fun markerPath(): Path {
    val child = SystemStarter.start(sh("mktemp -d")).assertSuccess()
    val folder = generateSequence { child.next() }
        .filterIsInstance<ChildEvent.Output>()
        .filter { it.stream == ChildStream.Stdout }
        .joinToString("") { it.text }
        .trim()
    return Path(folder, "marker")
}
