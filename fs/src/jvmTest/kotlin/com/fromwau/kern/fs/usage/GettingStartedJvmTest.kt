package com.fromwau.kern.fs.usage

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GettingStartedJvmTest {
    private val scratch = Files.createTempDirectory("getting-started").toFile()

    @AfterTest
    fun cleanUp() {
        scratch.deleteRecursively()
    }

    private val java = File(File(System.getProperty("java.home"), "bin"), "java").absolutePath

    @Test
    fun `getting started writes the default settings into the config folder current names`() {
        val process = ProcessBuilder(
            java,
            "-cp",
            System.getProperty("java.class.path"),
            GettingStarted::class.java.name,
        ).redirectErrorStream(true)
        process.environment().apply {
            put("HOME", File(scratch, "home").path)
            put("XDG_CONFIG_HOME", File(scratch, "config").path)
            put("XDG_DATA_HOME", File(scratch, "data").path)
            put("XDG_STATE_HOME", File(scratch, "state").path)
            put("XDG_CACHE_HOME", File(scratch, "cache").path)
            put("TMPDIR", File(scratch, "tmp").path)
            remove("XDG_RUNTIME_DIR")
        }
        val log = File(scratch, "output.txt")
        val run = process.redirectOutput(log).start()
        val finished = run.waitFor(RUN_LIMIT_SECONDS, TimeUnit.SECONDS)
        if (!finished) run.destroyForcibly()
        val output = log.readText()

        assertTrue(finished, "still running after $RUN_LIMIT_SECONDS s: $output")
        assertEquals(0, run.exitValue(), output)
        assertEquals("theme = \"light\"", File(scratch, "config/myapp/settings.toml").readText())
    }
}

private const val RUN_LIMIT_SECONDS = 120L
