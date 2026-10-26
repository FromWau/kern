package com.fromwau.kern.fs

import com.fromwau.kern.result.assertSuccess
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

class LongPathMirrorJvmTest {
    private val dir = newTempDir()
    private val src = dir / "src"
    private val dst = dir / "dst"
    private val name = "d".repeat(200)

    @AfterTest
    fun cleanUp() {
        // rm removes each entry by its folder, so it reaches below PATH_MAX where a path cannot.
        if (SystemFileSystem.exists(src)) {
            val _ = shell("rm -rf \"\$1\"", src)
        }
        dir.deleteTree()
    }

    /** Runs [script] in `bash` with [arg] as `$1`, and answers whether it exited 0. */
    private fun shell(
        script: String,
        arg: Path,
    ): Boolean = ProcessBuilder("bash", "-c", script, "bash", arg.toString())
        .inheritIO()
        .start()
        .waitFor() == 0

    @Test
    fun `a mirror of a source deeper than PATH_MAX fails the entry past it and makes the folders above it`() {
        createDirectoriesRaw(src)
        // Each folder is made from the one above it, so no path handed to the system grows past PATH_MAX. Bash's cd
        // gets that deep, where dash's fails.
        val script = "cd \"\$1\" && for i in \$(seq $LEVELS); do mkdir -m 755 $name && cd $name || exit 1; done && " +
            ": > deep.txt"
        val built = try {
            shell(script, src)
        } catch (_: IOException) {
            // No bash to build the tree with, as on Windows, leaves nothing to observe.
            return
        }
        check(built) { "the tree deeper than PATH_MAX could not be made" }

        val report = src.mirrorTo(dst.writer().assertSuccess()).assertSuccess()

        val failure = report.failures.single()
        assertEquals(IoCause.Other, assertIs<FileError.Inaccessible>(failure.error).cause)
        assertTrue(failure.path.toString().encodeToByteArray().size >= PATH_MAX_BYTES, failure.path.toString())
        assertTrue(SystemFileSystem.metadataOrNull(dst / checkNotNull(failure.relative.parent))?.isDirectory == true)
    }

    private companion object {
        /** Enough folders of 200 bytes each to take a path well past [PATH_MAX_BYTES]. */
        const val LEVELS = 25

        const val PATH_MAX_BYTES = 4096
    }
}
