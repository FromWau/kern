package com.fromwau.kern.fs

import com.fromwau.kern.fs.test.chmod
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class WriterModesLinuxTest {
    private val dir = newTempDir()

    @AfterTest
    fun cleanUp() = dir.deleteTree()

    @Test
    fun `a shared writer makes folders 755 and files 644 under a umask that lets the group write`() {
        val source = (dir / "source").writeRaw("copied")
        source.chmod(0b110_110_110).assertSuccess()

        withUmask(UMASK_002) {
            val made = (dir / "made" / "deep").writer().assertSuccess()
            made.writeText("a/b/f", "x").assertSuccess()
            made.writeText("run", "x", executable = true).assertSuccess()
            made.writeText("in-place", "x", inPlace = true).assertSuccess()
            made.copyFrom(source, "copy").assertSuccess()
            made.copyFrom(source, "kept", keepAttributes = true).assertSuccess()
            made.openAppend("log").assertSuccess().close()
            made.tryLock(".lock").assertSuccess().close()
            made.createDirectories("c/d").assertSuccess()
            made.writer("child").assertSuccess()
            made.createDirectoryAtomically("built") { it.writeText("f", "x").assertSuccess() }
        }.assertSuccess()

        val root = dir / "made" / "deep"
        val folders = listOf(dir / "made", root, root / "a", root / "a" / "b", root / "c", root / "c" / "d")
        val files = listOf("a/b/f", "in-place", "copy", "kept", "log", ".lock", "built/f").map { Path(root, it) }
        assertEquals(
            (folders + root / "child" + root / "built").associateWith { 0b111_101_101 } +
                files.associateWith { 0b110_100_100 } +
                mapOf(root / "run" to 0b111_101_101),
            (folders + root / "child" + root / "built" + files + root / "run").associateWith { it.mode() },
        )
    }

    @Test
    fun `a shared writer's root and file pass a trusted read under a umask that lets the group write`() {
        val read = withUmask(UMASK_002) {
            val config = (dir / "config").writer().assertSuccess()
            config.writeText("app.toml", "a = 1").assertSuccess()
            (config.root / "app.toml").readText(trusted = true)
        }

        assertEquals(Ok("a = 1"), read)
    }

    @Test
    fun `a write by rename keeps the mode of the file it replaces`() {
        val writer = dir.writer().assertSuccess()
        (dir / "shared.txt").writeRaw("old")
        (dir / "shared.txt").chmod(0b110_110_100).assertSuccess()

        withUmask(UMASK_002) { writer.writeText("shared.txt", "new") }.assertSuccess()

        assertEquals(0b110_110_100, (dir / "shared.txt").mode())
    }

    @Test
    fun `a private writer makes folders 700 and files 600 under a umask that lets the group write`() {
        withUmask(UMASK_002) {
            val private = (dir / "private").privateWriter().assertSuccess()
            private.writeText("a/f", "x")
        }.assertSuccess()

        assertEquals(
            mapOf(dir / "private" to 0b111_000_000, dir / "private" / "a" to 0b111_000_000),
            listOf(dir / "private", dir / "private" / "a").associateWith { it.mode() },
        )
        assertEquals(0b110_000_000, (dir / "private" / "a" / "f").mode())
    }
}
