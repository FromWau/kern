@file:OptIn(ExperimentalWasmJsInterop::class)

package com.fromwau.kern.terminal

import kotlin.js.ExperimentalWasmJsInterop

/**
 * Node's process streams when running under Node, and the console in a browser, which is never a terminal. Node
 * reports a broken pipe as an `error` event after the write returns, which would end the process unhandled; it is
 * kept instead and reported from the next write on.
 */
internal actual fun platformIo(): PlatformIo = if (isNode()) nodePlatformIo() else browserPlatformIo()

private fun nodePlatformIo(): PlatformIo {
    keepNodeStreamErrors()
    val out = NodeStream(Stream.Out)
    val err = NodeStream(Stream.Err)
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = nodeIsTty(stdout = true),
        errIsTty = nodeIsTty(stdout = false),
        width = nodeColumns().takeIf { it > 0 },
        ansiCapable = true,
        env = ::nodeEnv,
        failureOf = { if (it == Stream.Out) out.failure else err.failure },
        afterPendingWrites = { landed -> afterNodeWrites(stdout = true) { afterNodeWrites(stdout = false, landed) } },
    )
}

private fun browserPlatformIo(): PlatformIo = PlatformIo(
    // Each console call ends a line of its own, so the newline that ends the text would leave a blank one.
    writeOut = { consoleLog(it.removeSuffix("\n")) },
    writeErr = { consoleError(it.removeSuffix("\n")) },
    isTty = false,
    width = null,
    ansiCapable = false,
    env = { null },
)

/** Writes to Node's stream at once, keeps the first failure, thrown or reported by Node, and writes nothing after. */
private class NodeStream(private val stream: Stream) {
    private var thrown: WriteError? = null

    val failure: WriteError?
        get() = thrown ?: reported()

    fun write(text: String) {
        if (failure != null) return
        val message = nodeWrite(stdout = stream == Stream.Out, text = text) ?: return
        thrown = WriteError.Unknown(stream, message)
    }

    private fun reported(): WriteError? {
        val code = nodeErrorCode(stdout = stream == Stream.Out) ?: return null
        return if (code == "EPIPE") {
            WriteError.BrokenPipe(stream)
        } else {
            WriteError.Unknown(stream, nodeErrorMessage(stdout = stream == Stream.Out))
        }
    }
}

private fun isNode(): Boolean =
    js("typeof process !== 'undefined' && process.versions != null && typeof process.versions.node === 'string'")

private fun nodeIsTty(stdout: Boolean): Boolean = js("(stdout ? process.stdout : process.stderr).isTTY === true")

private fun nodeColumns(): Int = js("process.stdout.columns || process.stderr.columns || 0")

private fun nodeEnv(name: String): String? = js("process.env[name] === undefined ? null : process.env[name]")

/** Writes [text] to standard output or error, returning what the write threw, or null when it did not. */
private fun nodeWrite(stdout: Boolean, text: String): String? = js(
    "(() => { try { (stdout ? process.stdout : process.stderr).write(text); return null; } " +
        "catch (e) { return String(e && e.message || e); } })()",
)

// Process-wide, like the streams: one listener each, however many terminals are made, keeping the first error.
private fun keepNodeStreamErrors(): Unit = js(
    "[process.stdout, process.stderr].forEach(s => { const key = Symbol.for('kern.terminal.writeError'); " +
        "if (!(key in s)) { s[key] = null; s.on('error', e => { if (s[key] == null) s[key] = e; }); } })",
)

/** The code of the error Node reported on standard output or error, such as `EPIPE`; null while there is none. */
private fun nodeErrorCode(stdout: Boolean): String? = js(
    "(() => { const e = (stdout ? process.stdout : process.stderr)[Symbol.for('kern.terminal.writeError')]; " +
        "return e == null ? null : String(e.code || ''); })()",
)

private fun nodeErrorMessage(stdout: Boolean): String? = js(
    "(() => { const e = (stdout ? process.stdout : process.stderr)[Symbol.for('kern.terminal.writeError')]; " +
        "return e == null ? null : String(e.message || e); })()",
)

// Node runs write callbacks in order, so an empty write's callback comes after every earlier write's, and it gets
// their failure before the stream's error event does.
private fun afterNodeWrites(stdout: Boolean, callback: () -> Unit): Unit = js(
    "{ const s = stdout ? process.stdout : process.stderr; const key = Symbol.for('kern.terminal.writeError'); " +
        "try { s.write('', e => { if (e != null && s[key] == null) s[key] = e; callback(); }); } " +
        "catch (e) { if (s[key] == null) s[key] = e; callback(); } }",
)

private fun consoleLog(text: String): Unit = js("console.log(text)")

private fun consoleError(text: String): Unit = js("console.error(text)")

private val queue = AfterWritesQueue()

internal actual fun afterWritesQueue(): AfterWritesQueue = queue
