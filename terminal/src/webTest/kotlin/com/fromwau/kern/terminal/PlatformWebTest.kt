@file:OptIn(ExperimentalWasmJsInterop::class)

package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** Runs under Node, where the test task's output is captured rather than a terminal. */
class PlatformWebTest {

    @Test
    fun `writes to both streams land`() {
        val terminal = defaultTerminal()
        terminal.out("kern terminal test: standard output\n")
        terminal.err("kern terminal test: standard error\n")
        assertEquals(Ok(Unit), terminal.writeResult())
    }

    @Test
    fun `captured output is not a terminal`() {
        val io = platformIo()
        assertEquals(false, io.isTty)
        assertEquals(false, io.errIsTty)
    }

    @Test
    fun `a stream Node marks as a TTY is a terminal`() {
        setStdoutIsTty(true)
        try {
            assertEquals(true, platformIo().isTty)
            assertEquals(true, defaultTerminal().outIsTerminal)
        } finally {
            setStdoutIsTty(false)
        }
    }

    @Test
    fun `a closed pipe Node reports is a broken pipe on that stream alone`() {
        val terminal = defaultTerminal()
        emitStdoutError("EPIPE")
        try {
            assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.writeResult(Stream.Out))
            assertEquals(Ok(Unit), terminal.writeResult(Stream.Err))
        } finally {
            forgetStdoutError()
        }
    }

    @Test
    fun `a failure Node reports after the write returns is awaited`() = runTest {
        val terminal = defaultTerminal()
        failStdoutWritesLater("ENOSPC", "ENOSPC: no space left on device, write")
        try {
            terminal.out("lost\n")

            assertEquals(Ok(Unit), terminal.writeResult())
            assertEquals(
                Err(WriteError.Unknown(Stream.Out, "ENOSPC: no space left on device, write")),
                terminal.awaitWriteResult(),
            )
        } finally {
            restoreStdoutWrite()
            forgetStdoutError()
        }
    }

    @Test
    fun `the block after writes waits for Node to answer the writes before it`() = runTest {
        val terminal = defaultTerminal()
        failStdoutWritesLater("EIO", "EIO: i/o error, write")
        try {
            terminal.out("lost\n")

            val answer = suspendCoroutine { answered -> terminal.afterWrites { answered.resume(it) } }

            assertEquals(Err(WriteError.Unknown(Stream.Out, "EIO: i/o error, write")), answer)
        } finally {
            restoreStdoutWrite()
            forgetStdoutError()
        }
    }

    @Test
    fun `a closed pipe Node reports after the write returns is awaited as a broken pipe`() = runTest {
        val terminal = defaultTerminal()
        failStdoutWritesLater("EPIPE", "write EPIPE")
        try {
            terminal.out("lost\n")

            assertEquals(Err(WriteError.BrokenPipe(Stream.Out)), terminal.awaitWriteResult())
            assertEquals(Ok(Unit), terminal.writeResult(Stream.Err))
        } finally {
            restoreStdoutWrite()
            forgetStdoutError()
        }
    }

    @Test
    fun `a throw from the write that waits for the others is the write result`() = runTest {
        val terminal = defaultTerminal()
        throwOnEmptyStdoutWrite("write after end")
        try {
            terminal.out("landed\n")

            assertEquals(Err(WriteError.Unknown(Stream.Out, "write after end")), terminal.awaitWriteResult())
        } finally {
            restoreStdoutWrite()
            forgetStdoutError()
        }
    }

    @Test
    fun `under Node the code after a call runs before its block and a call from a block waits for Node`() = runTest {
        val terminal = defaultTerminal()
        val events = mutableListOf<String>()

        suspendCoroutine { finished ->
            terminal.afterWrites {
                events += "outer starts"
                terminal.afterWrites {
                    events += "inner"
                    finished.resume(Unit)
                }
                events += "outer ends"
            }
            events += "outermost call returned"
        }

        assertEquals(listOf("outermost call returned", "outer starts", "outer ends", "inner"), events)
    }

    @Test
    fun `each chunk of a long output waits for Node to answer the one before it`() = runTest {
        val terminal = defaultTerminal()
        answerStdoutWritesLater()
        try {
            val chunk = "x".repeat(65_536)

            val written = suspendCoroutine { finished ->
                terminal.outEach(sequenceOf(chunk, chunk, chunk)) { finished.resume(it) }
            }

            assertEquals(Ok(Unit), written)
            assertEquals(3, stdoutChunksWritten())
            assertEquals(false, stdoutChunkWrittenEarly())
        } finally {
            restoreStdoutWrite()
        }
    }

    @Test
    fun `the environment is read from the host`() {
        assertNotNull(platformIo().env("PATH"))
    }

    @Test
    fun `a file read as standard input comes through byte for byte`() {
        val bytes = byteArrayOf(
            0xEF.toByte(),
            0xBB.toByte(),
            0xBF.toByte(),
            'a'.code.toByte(),
            0x0D,
            0x0A,
            0xFF.toByte(),
        )
        val fd = openTempFileHolding(bytes.joinToString("") { (it.toInt() and 0xFF).toChar().toString() })
        try {
            assertContentEquals(bytes, readAll(maxBytes = null) { nodeReadChunk(fd, it) }.assertSuccess())
        } finally {
            closeAndRemoveTempFile(fd)
        }
    }

    @Test
    fun `a directory read as standard input is IsADirectory`() {
        val fd = openTempDirectory()
        try {
            readAll(maxBytes = null) { nodeReadChunk(fd, it) }.assertError<ReadError.IsADirectory>()
        } finally {
            closeFd(fd)
        }
    }
}

// The text's characters are written as Latin-1, one byte each, so it can hold any byte.
private fun openTempFileHolding(latin1: String): Int = js(
    """(() => {
        const fs = process.getBuiltinModule('fs');
        const path = process.getBuiltinModule('path').join(
            process.getBuiltinModule('os').tmpdir(), 'kern-terminal-stdin-' + process.pid + '-' + Date.now());
        fs.writeFileSync(path, Buffer.from(latin1, 'latin1'));
        const fd = fs.openSync(path, 'r');
        globalThis.kernTempPaths = globalThis.kernTempPaths || {};
        globalThis.kernTempPaths[fd] = path;
        return fd;
    })()""",
)

private fun closeAndRemoveTempFile(fd: Int): Unit = js(
    "{ const fs = process.getBuiltinModule('fs'); fs.closeSync(fd); fs.rmSync(globalThis.kernTempPaths[fd]); }",
)

private fun openTempDirectory(): Int =
    js("process.getBuiltinModule('fs').openSync(process.getBuiltinModule('os').tmpdir(), 'r')")

private fun closeFd(fd: Int): Unit = js("process.getBuiltinModule('fs').closeSync(fd)")

private fun setStdoutIsTty(value: Boolean): Unit = js("{ process.stdout.isTTY = value ? true : undefined; }")

// As Node does when the reader of a pipe has gone: an error event after the write returned.
private fun emitStdoutError(code: String): Unit =
    js("{ const e = new Error('write ' + code); e.code = code; process.stdout.emit('error', e); }")

// As Node does for a full disk or a closed pipe: the write returns, and the failure comes later, to the callback of
// every write still waiting and then as the stream's error event. Callbacks run in the order the writes were made.
// Only text fails, so an empty write learns of the failure only by waiting for the one before it. Nothing reaches
// the real standard output, and once restoreStdoutWrite puts the double away, its late callbacks do nothing, so a
// test that ends before them cannot leave a failure for the next one.
private fun failStdoutWritesLater(code: String, message: String): Unit = js(
    """{
        const s = process.stdout;
        s.kernRealWrite = s.write;
        let failure = null;
        let previous = Promise.resolve();
        let putAway = false;
        s.kernPutDoubleAway = () => { putAway = true; };
        s.write = function (chunk, encoding, callback) {
            const done = typeof encoding === 'function' ? encoding : callback;
            previous = previous.then(() => new Promise(landed => setTimeout(landed, 10))).then(() => {
                if (putAway) return;
                if (chunk.length > 0 && failure == null) {
                    failure = new Error(message);
                    failure.code = code;
                    if (done) done(failure);
                    s.emit('error', failure);
                } else if (done) {
                    done(failure);
                }
            });
            return true;
        };
    }""",
)

// Like Node, each write is answered later, in order; it notes a chunk written while an earlier write is unanswered.
private fun answerStdoutWritesLater(): Unit = js(
    """{
        const s = process.stdout;
        s.kernRealWrite = s.write;
        let previous = Promise.resolve();
        let unanswered = 0;
        let putAway = false;
        s.kernChunks = 0;
        s.kernEarly = false;
        s.kernPutDoubleAway = () => { putAway = true; };
        s.write = function (chunk, encoding, callback) {
            const done = typeof encoding === 'function' ? encoding : callback;
            if (chunk.length > 0) {
                if (unanswered > 0) s.kernEarly = true;
                s.kernChunks++;
            }
            unanswered++;
            previous = previous.then(() => new Promise(answered => setTimeout(answered, 5))).then(() => {
                if (putAway) return;
                unanswered--;
                if (done) done(null);
            });
            return true;
        };
    }""",
)

private fun stdoutChunksWritten(): Int = js("process.stdout.kernChunks")

private fun stdoutChunkWrittenEarly(): Boolean = js("process.stdout.kernEarly")

// As a stream that throws on any write, here only on the empty one, so the text before it lands.
private fun throwOnEmptyStdoutWrite(message: String): Unit = js(
    """{
        const s = process.stdout;
        s.kernRealWrite = s.write;
        s.write = function (chunk) {
            if (chunk.length === 0) throw new Error(message);
            return true;
        };
    }""",
)

private fun restoreStdoutWrite(): Unit = js(
    "{ const s = process.stdout; if (s.kernPutDoubleAway) s.kernPutDoubleAway(); delete s.kernPutDoubleAway; " +
        "s.write = s.kernRealWrite; }",
)

private fun forgetStdoutError(): Unit = js("{ process.stdout[Symbol.for('kern.terminal.writeError')] = null; }")
