package com.fromwau.kern.logger

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class LogFileFailureTest {
    private val time = TestTimeSource()
    private val reports = mutableListOf<String>()
    private val console = mutableListOf<String>()
    private val recorder = mutableListOf<String>()
    private val recording = LogSink { entry, _ -> recorder += entry.message }

    private val created = mutableListOf<Path>()
    private var sequence = 0

    private fun logger(vararg sinks: LogSink) =
        Logger(sinks.toList(), { entry, _, _ -> console += entry.message }, report = { reports += it }, timeSource = time)

    @AfterTest
    fun cleanup() {
        created.reversed().forEach { if (SystemFileSystem.exists(it)) SystemFileSystem.delete(it) }
    }

    /** A log path that cannot be written until [unblock] is called: its parent is a regular file. */
    private fun blockedFile(): Path {
        val blocker = Path(SystemTemporaryDirectory, "kern-logger-blocker-${sequence++}")
        if (SystemFileSystem.exists(blocker)) SystemFileSystem.delete(blocker)
        SystemFileSystem.sink(blocker).close()
        val file = Path(blocker, "app.log")
        created += blocker
        created += file
        return file
    }

    private fun unblock(file: Path) {
        SystemFileSystem.delete(file.parent!!)
    }

    private fun lines(file: Path): List<String> =
        SystemFileSystem.source(file).buffered().use { it.readString() }.lines().filter { it.isNotEmpty() }

    private fun config(file: FileOutput) = LoggerConfig(console = ConsoleOutput.Platform(), file = file)

    @Test
    fun `health does not change while every write succeeds`() {
        val file = Path(SystemTemporaryDirectory, "kern-logger-healthy-${sequence++}.log").also { created += it }
        val logger = logger(recording)
        logger.configure(config(file = FileOutput.Append(file)))
        val before = logger.health.value

        repeat(5) { logger.tag("app").i { "entry $it" } }
        logger.close()

        assertSame(before, logger.health.value)
    }

    @Test
    fun `a refused file entry is held and written in order once the file recovers`() {
        val file = blockedFile()
        val logger = logger(recording)
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Retry(backoff = 1.seconds))))

        logger.tag("app").i { "a" }
        logger.tag("app").i { "b" }
        unblock(file)
        logger.tag("app").i { "c" }
        val held = logger.health.value.file
        assertEquals(BufferHealth(failures = 1, buffered = 3, lastError = held.lastError), held)

        time += 1.seconds
        logger.tag("app").i { "d" }
        logger.close()

        assertEquals(listOf("a", "b", "c", "d"), lines(file).map { it.substringAfter(" - ") })
        assertEquals(listOf("a", "b", "c", "d"), recorder, "the sinks never wait for the file")
        assertEquals(0, logger.health.value.file.buffered)
        assertEquals(1, reports.size)
        assertContains(reports.single(), "held for a retry")
    }

    @Test
    fun `a full retry buffer drops the oldest and a gap line stands where they were`() {
        val file = blockedFile()
        val logger = logger()
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Retry(capacity = 2, backoff = 1.seconds))))

        listOf("a", "b", "c", "d").forEach { logger.tag("app").i { it } }
        unblock(file)
        time += 1.seconds
        logger.tag("app").i { "e" }
        logger.close()

        val written = lines(file).map { it.substringAfter(" - ") }
        assertContains(written[0], "dropped 2 entries the log file refused")
        assertEquals(listOf("c", "d", "e"), written.drop(1))
        assertEquals(2, logger.health.value.file.dropped)
    }

    @Test
    fun `dropping the newest keeps the lead-up and puts the gap line after it`() {
        val file = blockedFile()
        val logger = logger()
        val retry = FileFailure.Retry(capacity = 2, drop = DropPolicy.NEWEST, backoff = 1.seconds)
        logger.configure(config(file = FileOutput.Append(file, retry)))

        listOf("a", "b", "c", "d").forEach { logger.tag("app").i { it } }
        unblock(file)
        time += 1.seconds
        logger.tag("app").i { "e" }
        logger.close()

        val written = lines(file).map { it.substringAfter(" - ") }
        assertEquals(listOf("a", "b"), written.take(2))
        assertContains(written[2], "dropped 2 entries the log file refused")
        assertEquals("e", written[3])
    }

    @Test
    fun `FileFailure Drop loses refused entries and says so once the file recovers`() {
        val file = blockedFile()
        val logger = logger()
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Drop)))

        logger.tag("app").i { "a" }
        logger.tag("app").i { "b" }
        unblock(file)
        logger.tag("app").i { "c" }
        logger.close()

        val written = lines(file).map { it.substringAfter(" - ") }
        assertContains(written[0], "dropped 2 entries")
        assertEquals("c", written[1])
        assertEquals(2, logger.health.value.file.failures, "with nothing held, every entry tries the file")
        assertContains(reports.single(), "dropped")
    }

    @Test
    fun `FileFailure Throw reaches the caller after the console and the sinks have the entry`() {
        val file = blockedFile()
        val logger = logger(recording)
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Throw)))

        assertFailsWith<Exception> { logger.tag("audit").i { "must not vanish" } }

        assertEquals(listOf("must not vanish"), recorder)
        assertEquals(listOf("must not vanish"), console)
        assertTrue(reports.isEmpty(), "a thrown failure is the caller's to report")
    }

    @Test
    fun `close with the file still refusing reports the lost entries in one line`() {
        val file = blockedFile()
        val logger = logger()
        logger.configure(config(file = FileOutput.Append(file)))

        repeat(3) { logger.tag("app").i { "entry $it" } }
        logger.close()

        assertEquals(2, reports.size, "the first failure, then one summary: $reports")
        assertContains(reports.last(), "3 entries for")
        val health = logger.health.value.file
        assertEquals(BufferHealth(failures = 2, buffered = 0, dropped = 3, lastError = health.lastError), health)
    }

    @Test
    fun `moving the file writes what the old path holds there first`() {
        val old = blockedFile()
        val new = Path(SystemTemporaryDirectory, "kern-logger-moved-${sequence++}.log").also { created += it }
        val logger = logger()
        logger.configure(config(file = FileOutput.Append(old)))
        logger.tag("app").i { "for the old file" }

        unblock(old)
        logger.configure(config(file = FileOutput.Append(new)))
        logger.tag("app").i { "for the new file" }
        logger.close()

        assertEquals(listOf("for the old file"), lines(old).map { it.substringAfter(" - ") })
        assertEquals(listOf("for the new file"), lines(new).map { it.substringAfter(" - ") })
    }

    @Test
    fun `switching to FileFailure Throw during a backoff writes what was held instead of dropping it`() {
        val file = blockedFile()
        val logger = logger()
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Retry(backoff = 60.seconds))))
        listOf("a", "b", "c").forEach { logger.tag("app").i { it } }

        unblock(file)
        logger.configure(config(file = FileOutput.Append(file, FileFailure.Throw)))
        logger.tag("app").i { "d" }
        logger.close()

        assertEquals(listOf("a", "b", "c", "d"), lines(file).map { it.substringAfter(" - ") })
    }
}
