@file:OptIn(ExperimentalWasmInterop::class, UnsafeWasmMemoryApi::class)

package com.fromwau.kern.terminal

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import kotlin.wasm.ExperimentalWasmInterop
import kotlin.wasm.WasmImport
import kotlin.wasm.unsafe.MemoryAllocator
import kotlin.wasm.unsafe.Pointer
import kotlin.wasm.unsafe.UnsafeWasmMemoryApi
import kotlin.wasm.unsafe.withScopedMemoryAllocator

/**
 * Standard input, output and error through WASI's file descriptors 0, 1 and 2. WASI has no way to ask for the width.
 */
internal actual fun platformIo(): PlatformIo {
    val out = WasiStream(STDOUT, Stream.Out)
    val err = WasiStream(STDERR, Stream.Err)
    val environment by lazy { readEnvironment() }
    return PlatformIo(
        writeOut = out::write,
        writeErr = err::write,
        isTty = isTerminal(STDOUT),
        errIsTty = isTerminal(STDERR),
        width = null,
        ansiCapable = true,
        env = { environment[it] },
        failureOf = { if (it == Stream.Out) out.failure else err.failure },
        inIsTty = isTerminal(STDIN),
        readInChunk = { buffer -> readChunk(STDIN, buffer) },
    )
}

/** Reads the next bytes of [fd] into [buffer]: how many, 0 at its end, or why the host refused. */
internal fun readChunk(fd: Int, buffer: ByteArray): Result<Int, ReadError> = withScopedMemoryAllocator { allocator ->
    val memory = allocator.allocate(buffer.size)
    val iovec = allocator.allocate(8)
    val read = allocator.allocate(4)
    iovec.storeInt(memory.address.toInt())
    (iovec + 4).storeInt(buffer.size)
    var errno: Int
    do {
        errno = fdRead(fd, iovec.address.toInt(), 1, read.address.toInt())
    } while (errno == ERRNO_INTR)
    when (errno) {
        SUCCESS -> {
            val count = read.loadInt()
            for (index in 0 until count) buffer[index] = (memory + index).loadByte()
            Ok(count)
        }
        ERRNO_ISDIR -> Err(ReadError.IsADirectory)
        else -> Err(ReadError.Refused("WASI errno $errno"))
    }
}

/** Writes to [fd] at once, keeps the first write the host refused, and writes nothing after it. */
internal class WasiStream(private val fd: Int, private val stream: Stream) {
    var failure: WriteError? = null
        private set

    fun write(text: String) {
        if (failure != null) return
        val errno = writeAll(fd, text.encodeToByteArray())
        failure = when (errno) {
            SUCCESS -> null
            ERRNO_PIPE -> WriteError.BrokenPipe(stream)
            WROTE_NONE -> WriteError.Refused(stream, "WASI wrote none of the bytes left")
            else -> WriteError.Refused(stream, "WASI errno $errno")
        }
    }
}

/**
 * Writes every byte of [bytes], however many calls the host takes. Returns the WASI errno, 0 on success, or
 * [WROTE_NONE] when a call took none of them.
 */
private fun writeAll(fd: Int, bytes: ByteArray): Int = withScopedMemoryAllocator { allocator ->
    val buffer = allocator.copyOf(bytes)
    val iovec = allocator.allocate(8)
    val written = allocator.allocate(4)
    var offset = 0
    while (offset < bytes.size) {
        iovec.storeInt((buffer + offset).address.toInt())
        (iovec + 4).storeInt(bytes.size - offset)
        val errno = fdWrite(fd, iovec.address.toInt(), 1, written.address.toInt())
        if (errno != SUCCESS) return@withScopedMemoryAllocator errno
        // Asked again, it would take nothing again, forever.
        if (written.loadInt() == 0) return@withScopedMemoryAllocator WROTE_NONE
        offset += written.loadInt()
    }
    SUCCESS
}

// The test wasi-libc's isatty makes: a character device that can neither seek nor tell.
private fun isTerminal(fd: Int): Boolean = withScopedMemoryAllocator { allocator ->
    val stat = allocator.allocate(FDSTAT_SIZE)
    if (fdFdstatGet(fd, stat.address.toInt()) != SUCCESS) return@withScopedMemoryAllocator false
    val rightsBase = (stat + 8).loadLong()
    stat.loadByte() == FILETYPE_CHARACTER_DEVICE && rightsBase and (RIGHT_FD_SEEK or RIGHT_FD_TELL) == 0L
}

/** The process environment as the host passed it, or empty when it refused to tell. */
private fun readEnvironment(): Map<String, String> = withScopedMemoryAllocator { allocator ->
    val sizes = allocator.allocate(8)
    if (environSizesGet(sizes.address.toInt(), (sizes + 4).address.toInt()) != SUCCESS) {
        return@withScopedMemoryAllocator emptyMap()
    }
    val count = sizes.loadInt()
    val bufferSize = (sizes + 4).loadInt()
    if (count == 0) return@withScopedMemoryAllocator emptyMap()
    val pointers = allocator.allocate(count * 4)
    val buffer = allocator.allocate(bufferSize)
    if (environGet(pointers.address.toInt(), buffer.address.toInt()) != SUCCESS) {
        return@withScopedMemoryAllocator emptyMap()
    }
    val bytes = ByteArray(bufferSize) { (buffer + it).loadByte() }
    // Each variable is a NAME=value pair ending in a NUL, packed one after another.
    bytes.decodeToString()
        .split('\u0000')
        .filter { '=' in it }
        .associate { it.substringBefore('=') to it.substringAfter('=') }
}

private fun MemoryAllocator.copyOf(bytes: ByteArray): Pointer {
    val pointer = allocate(bytes.size)
    bytes.forEachIndexed { index, byte -> (pointer + index).storeByte(byte) }
    return pointer
}

@WasmImport("wasi_snapshot_preview1", "fd_write")
private external fun fdWrite(fd: Int, iovs: Int, iovsLength: Int, written: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_read")
private external fun fdRead(fd: Int, iovs: Int, iovsLength: Int, read: Int): Int

@WasmImport("wasi_snapshot_preview1", "fd_fdstat_get")
private external fun fdFdstatGet(fd: Int, stat: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_sizes_get")
private external fun environSizesGet(count: Int, bufferSize: Int): Int

@WasmImport("wasi_snapshot_preview1", "environ_get")
private external fun environGet(pointers: Int, buffer: Int): Int

private const val STDIN = 0
private const val STDOUT = 1
private const val STDERR = 2
private const val SUCCESS = 0
private const val ERRNO_INTR = 27
private const val ERRNO_ISDIR = 31
private const val ERRNO_PIPE = 64

// No WASI errno is negative.
private const val WROTE_NONE = -1
private const val FDSTAT_SIZE = 24
private const val FILETYPE_CHARACTER_DEVICE: Byte = 2
private const val RIGHT_FD_SEEK = 1L shl 2
private const val RIGHT_FD_TELL = 1L shl 5

private val queue = AfterWritesQueue()

internal actual fun afterWritesQueue(): AfterWritesQueue = queue
