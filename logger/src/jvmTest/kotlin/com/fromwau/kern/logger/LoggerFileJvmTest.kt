package com.fromwau.kern.logger

import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LoggerFileJvmTest {
    private val file = Path(SystemTemporaryDirectory, "kern-logger-two-writers-${System.nanoTime()}.log")

    @AfterTest
    fun cleanup() {
        if (SystemFileSystem.exists(file)) SystemFileSystem.delete(file)
    }

    @Test
    fun `two loggers appending to one file never tear an entry`() {
        // Well past an 8 KB buffer, so an entry written in more than one call would interleave and tear.
        val message = "x".repeat(20_000)
        val writers = List(2) { index ->
            val logger = Logger()
            logger.configure(LoggerConfig(format = LogFormat.JSON, console = Console.OFF, file = file))
            thread {
                repeat(300) { logger.tag("writer$index").i { message } }
                logger.close()
            }
        }
        writers.forEach { it.join() }

        val lines = File(file.toString()).readLines()

        assertEquals(600, lines.size)
        lines.forEach { Json.parseToJsonElement(it) }
    }
}
