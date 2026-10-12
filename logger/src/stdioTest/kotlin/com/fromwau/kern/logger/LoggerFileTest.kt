package com.fromwau.kern.logger

import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoggerFileTest {

    private val logger = Logger()

    private val created = mutableListOf<Path>()
    private var sequence = 0

    @AfterTest
    fun cleanup() {
        logger.close()
        created.forEach { if (SystemFileSystem.exists(it)) SystemFileSystem.delete(it) }
    }

    private fun quiet(level: LogLevel) = LoggerConfig(level = level, console = ConsoleOutput.Off)

    private fun tempFile(name: String): Path {
        val path = Path(SystemTemporaryDirectory, "kern-logger-$name-${sequence++}.log")
        if (SystemFileSystem.exists(path)) SystemFileSystem.delete(path)
        created += path
        return path
    }

    private fun read(path: Path): String =
        SystemFileSystem.source(path).buffered().use { it.readString() }

    @Test
    fun `after a failed write the next entry starts on a line of its own`() {
        val file = tempFile("torn")
        SystemFileSystem.createDirectories(file)
        logger.configure(quiet(LogLevel.INFO).copy(file = FileOutput.Append(file, FileFailure.Drop)))
        logger.tag("app").i { "fails: the path is a directory" }

        SystemFileSystem.delete(file)
        SystemFileSystem.sink(file).buffered().use { it.writeString("{\"torn") }
        logger.tag("app").i { "readable" }
        logger.close()

        val lines = read(file).lines()
        assertEquals("{\"torn", lines[0])
        assertContains(lines[1], "dropped 1 entry the log file refused")
        assertContains(lines[2], "readable")
    }

    @Test
    fun `the configured file receives the rendered line`() {
        val file = tempFile("basic")
        logger.configure(quiet(LogLevel.INFO).copy(file = FileOutput.Append(file)))

        logger.tag("app").i("count" to 412) { "written to disk" }
        logger.close()

        val written = read(file)
        assertContains(written, "written to disk")
        assertContains(written, "count=412")
    }

    @Test
    fun `moving the configured file writes the next entry to the new path`() {
        val first = tempFile("first")
        val second = tempFile("second")
        logger.configure(quiet(LogLevel.INFO).copy(file = FileOutput.Append(first)))

        logger.tag("app").i { "in the first file" }
        logger.configure(quiet(LogLevel.INFO).copy(file = FileOutput.Append(second)))
        logger.tag("app").i { "in the second file" }
        logger.close()

        assertContains(read(first), "in the first file")
        assertContains(read(second), "in the second file")
        assertFalse(read(first).contains("in the second file"))
    }

    @Test
    fun `a missing parent directory is created rather than failing the write`() {
        val nested = Path(SystemTemporaryDirectory, "kern-logger-nested-${sequence++}")
        val file = Path(nested, "app.log")
        created += file
        logger.configure(quiet(LogLevel.INFO).copy(file = FileOutput.Append(file)))

        logger.tag("app").i { "into a fresh directory" }
        logger.close()

        assertContains(read(file), "into a fresh directory")

        SystemFileSystem.delete(file)
        SystemFileSystem.delete(nested)
    }

    @Test
    fun `the file and the console each render in their own format`() {
        val file = tempFile("formats")
        val console = mutableListOf<String>()
        val logger = Logger(emptyList(), { _, line, _ -> console += line })
        logger.configure(
            LoggerConfig(
                console = ConsoleOutput.Stderr(format = LogFormat.TEXT),
                file = FileOutput.Append(file, format = LogFormat.JSON),
            ),
        )

        logger.tag("app").i("count" to 412) { "both" }
        logger.close()

        assertTrue(read(file).trim().startsWith("{"), "the file gets JSON: ${read(file)}")
        assertContains(console.single(), "INFO    app - both count=412")
    }
}
