package com.fromwau.kern.exec

import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path

/** Starts this command and returns every event its child reports, failing the test when it does not start. */
fun Command.events(): List<ChildEvent> = SystemStarter.start(this).assertSuccess().drain()

/** Takes events until the child has none left. */
fun Child.drain(): List<ChildEvent> = generateSequence { next() }.toList()

/** Everything written to [stream], joined. */
fun List<ChildEvent>.text(stream: ChildStream): String =
    filterIsInstance<ChildEvent.Output>().filter { it.stream == stream }.joinToString("") { it.text }

/** `/bin/sh -c script sh args...`: the script sees [args] as `$1`, `$2` and on. */
fun sh(script: String, vararg args: String) = Command(listOf("/bin/sh", "-c", script, "sh") + args)

/** A new empty folder, made by `mktemp -d`, without symbolic links in its path so `pwd -P` gives it back. */
fun temporaryFolder(): Path = Path(sh("cd \"$(mktemp -d)\" && pwd -P").events().text(ChildStream.Stdout).trim())

/** Runs [test] with a new empty folder, removed afterwards. */
fun withFolder(test: (Path) -> Unit) {
    val folder = temporaryFolder()
    try {
        test(folder)
    } finally {
        folder.removeAll()
    }
}

/** Removes this folder and everything in it. */
fun Path.removeAll() {
    val events = sh("rm -rf \"$1\"", toString()).events()
    check(ChildEvent.Exited(0) in events) { "removing $this: $events" }
}

/** The value a test gives `KERN_RAW` before [rawVariableCheck]: `a`, the byte 0xFF, `b`, which is not UTF-8. */
val rawVariableBytes: ByteArray = byteArrayOf('a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte())

/** Prints `same` when the child started with [environment] sees `KERN_RAW` as [rawVariableBytes], else `changed`. */
fun rawVariableCheck(environment: Environment): String = Command(
    listOf("/bin/sh", "-c", "if [ \"\$KERN_RAW\" = \"\$(printf 'a\\377b')\" ]; then echo same; else echo changed; fi"),
    environment = environment,
).events().text(ChildStream.Stdout).trim()

/** The environments that pass this process's variables on. */
val inheritingEnvironments: List<Environment> =
    listOf(Environment.Inherit, Environment.InheritWith(set = mapOf("KERN_OTHER" to "1")))
