@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.logger.file

import com.fromwau.kern.fs.FileError
import com.fromwau.kern.fs.FileType
import com.fromwau.kern.fs.IoCause
import com.fromwau.kern.logger.SinkConfig
import com.fromwau.kern.logger.SinkError
import com.fromwau.kern.logger.SinkFailure
import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertError
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import platform.linux.RLIMIT_FSIZE
import platform.linux.getrlimit
import platform.linux.rlimit
import platform.linux.setrlimit
import platform.posix.SIGXFSZ
import platform.posix.signal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class FileSinkLinuxTest {
    private val logger = testLogger()
    private val dir = newTempDir()
    private val file = Path(dir, "app.log")

    @AfterTest
    fun cleanup() {
        val _ = logger.close()
        dir.deleteTree()
    }

    @Test
    fun `a write the disk refuses fails with the system's exception`() {
        val sink = FileSink(file)

        val written = withFileSizeLimit(bytes = 4) { sink.write(entry, SinkConfig()) }
        val _ = sink.close()

        val dropped = written.assertError<SinkError.Dropped>()
        val refused = assertIs<FileError.WriteFailed>(dropped.error)
        assertEquals(file, refused.path)
        assertEquals(IoCause.Other, refused.cause)
        assertEquals("$file: File too large", refused.exception?.message)
    }

    @Test
    fun `a full disk shows in the sink's health`() = runTest {
        val installed = logger.addOrFail(FileSink(file, onFailure = SinkFailure.Drop()))

        withFileSizeLimit(bytes = 4) {
            logger.tag("app").i { "lost" }
            eventually { installed.health.value.failures > 0 }
        }

        val dropped = assertIs<SinkError.Dropped>(installed.health.value.lastError)
        assertIs<FileError.WriteFailed>(dropped.error)
    }

    @Test
    fun `a device at the path fails with NotRegularFile`() {
        val device = Path("/dev/full")
        val sink = FileSink(device)

        val written = sink.write(entry, SinkConfig())
        val _ = sink.close()

        assertEquals(Err(SinkError.Dropped(FileError.NotRegularFile(device, FileType.CharacterDevice))), written)
    }
}

/** Runs [block] with every write past [bytes] into one file failing with EFBIG, as a full disk fails it. */
private inline fun <T> withFileSizeLimit(
    bytes: Long,
    block: () -> T,
): T {
    // Without a handler, crossing the limit ends the process with SIGXFSZ instead of failing the write.
    val previousHandler = signal(SIGXFSZ, staticCFunction<Int, Unit> { })
    return memScoped {
        val previous = alloc<rlimit>()
        check(getrlimit(RLIMIT_FSIZE, previous.ptr) == 0)
        val limited = alloc<rlimit>()
        limited.rlim_cur = bytes.convert()
        limited.rlim_max = previous.rlim_max
        check(setrlimit(RLIMIT_FSIZE, limited.ptr) == 0)
        try {
            block()
        } finally {
            setrlimit(RLIMIT_FSIZE, previous.ptr)
            signal(SIGXFSZ, previousHandler)
        }
    }
}
