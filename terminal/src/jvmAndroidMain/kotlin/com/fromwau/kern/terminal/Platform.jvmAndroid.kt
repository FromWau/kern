package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.getOrSet

/**
 * Shared JVM/Android stdio; the platform actual supplies the sinks and the [isTty] probe, which is all
 * that differs per platform (isTerminal() vs console presence). Standard input is System.in, so a program that
 * replaced it through System.setIn reads its replacement.
 */
internal fun jvmPlatformIo(
    isTty: Boolean,
    outSink: OutputStream,
    errSink: OutputStream,
    inSource: InputStream = System.`in`,
): PlatformIo {
    // A sink per terminal rather than System.out/System.err: a failure recorded on a process-wide stream
    // would decide every later terminal's answer too.
    val out = StreamSink(outSink, Stream.Out)
    val err = StreamSink(errSink, Stream.Err)
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = isTty,
        // No ioctl without JNI, so the COLUMNS env var handled by resolveColumns is the only width source.
        width = null,
        ansiCapable = true,
        env = { System.getenv(it) },
        failureOf = { if (it == Stream.Out) out.failure else err.failure },
        inIsTty = isTty,
        readInChunk = { buffer -> readChunk(inSource, buffer) },
    )
}

private fun readChunk(source: InputStream, buffer: ByteArray): Result<Int, ReadError> = try {
    Ok(source.read(buffer).coerceAtLeast(0))
} catch (e: IOException) {
    // The JDK raises a directory and a failing device alike as a plain IOException, with no errno to tell them apart.
    Err(ReadError.Unknown(e.message, e))
}

/** Writes UTF-8 to [sink] at once, keeps the first failure a PrintStream would swallow, and writes nothing after it. */
internal class StreamSink(
    private val sink: OutputStream,
    private val stream: Stream,
) {
    @Volatile
    var failure: WriteError? = null
        private set

    @Synchronized
    fun write(text: String) {
        if (failure != null) return
        try {
            sink.write(text.encodeToByteArray())
            sink.flush()
        } catch (e: IOException) {
            // The JDK raises every failed write as a plain IOException, with no errno to tell a closed pipe
            // from a full disk.
            failure = WriteError.Unknown(stream, e.message, e)
        }
    }
}

private val queues = ThreadLocal<AfterWritesQueue>()

internal actual fun afterWritesQueue(): AfterWritesQueue = queues.getOrSet(::AfterWritesQueue)
