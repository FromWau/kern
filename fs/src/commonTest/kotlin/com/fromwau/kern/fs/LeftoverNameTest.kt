package com.fromwau.kern.fs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.map
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.io.files.Path

/** Names shaped like the copies a write leaves: what [nameProblem] says of them, and which [Writer] calls take them. */
class LeftoverNameTest {
    private val dir = newTempDir()
    private val rootPath = (dir / "root").also { createDirectoriesRaw(it) }
    private val source = (dir / "source.txt").writeRaw("copied")
    private val writer = rootPath.writer().assertSuccess()
    private val root = rootPath.realPath().assertSuccess()

    @AfterTest
    fun cleanUp() {
        dir.deleteTree()
    }

    private val temp = ".a.md.$A_WRITE_ID.tmp"

    /** Runs [op] with [name] and checks it is refused as a leftover's name with nothing touched. */
    private fun assertRefusedToMake(
        name: String = temp,
        op: (String) -> Result<Any?, FileError>,
    ) {
        val before = dir.tree()
        val problem = RelativePathProblem.BadName(0, name, NameProblem.LeftoverName)

        assertEquals(Err(FileError.InvalidRelativePath(root, name, problem)), op(name))
        assertEquals(before, dir.tree())
    }

    // --- the name check

    @Test
    fun `each name a write leaves beside its file is LeftoverName`() {
        val names = listOf(
            ".a.md.$A_WRITE_ID.tmp",
            ".a.md.$A_WRITE_ID.bak",
            ".a.md.$A_WRITE_ID.bak.part",
            ".pkg.$A_WRITE_ID.dir",
            ".current.$A_WRITE_ID.link",
            ".$A_WRITE_ID.tmp",
        )

        names.forEach { assertEquals(NameProblem.LeftoverName, it.nameProblem(), it) }
    }

    @Test
    fun `a name not shaped like a leftover has no problem`() {
        assertNull(".a.md.tmp".nameProblem())
        assertNull("a.md.$A_WRITE_ID.tmp".nameProblem())
        assertNull(".a.md.${A_WRITE_ID.uppercase()}.bak".nameProblem())
        assertNull(".a.md.$A_WRITE_ID.lock".nameProblem())
        assertNull(".hidden".nameProblem())
    }

    @Test
    fun `a name too long and shaped like a leftover is TooLong`() {
        assertEquals(NameProblem.TooLong, ".${"a".repeat(250)}.$A_WRITE_ID.tmp".nameProblem())
    }

    @Test
    fun `a leftover's name below a folder is BadName LeftoverName at its index`() {
        val name = ".2.0.$A_WRITE_ID.dir"

        val problem = RelativePathProblem.BadName(1, name, NameProblem.LeftoverName)
        assertEquals(problem, "tool/$name/manifest.toml".relativePathProblem())
    }

    // --- calls that make an entry

    @Test
    fun `writeText refuses each name a write leaves`() {
        listOf(temp, ".a.md.$A_WRITE_ID.bak", ".a.md.$A_WRITE_ID.bak.part", ".$A_WRITE_ID.tmp").forEach { name ->
            assertRefusedToMake(name) { writer.writeText(it, "x") }
        }
    }

    @Test
    fun `writeBytes refuses a leftover's name`() = assertRefusedToMake { writer.writeBytes(it, byteArrayOf(1)) }

    @Test
    fun `copyFrom refuses a leftover's name`() = assertRefusedToMake { writer.copyFrom(source, it) }

    @Test
    fun `openAppend refuses to make a file with a leftover's name`() = assertRefusedToMake { name ->
        writer.openAppend(name).map { it.close() }
    }

    @Test
    fun `tryLock refuses to make a lock file with a leftover's name`() = assertRefusedToMake { name ->
        writer.tryLock(name).map { it.close() }
    }

    @Test
    fun `createDirectories refuses a leftover's name`() = assertRefusedToMake(".pkg.$A_WRITE_ID.dir") {
        writer.createDirectories(it)
    }

    @Test
    fun `createDirectoryAtomically refuses a leftover's name`() = assertRefusedToMake(".pkg.$A_WRITE_ID.dir") {
        writer.createDirectoryAtomically(it) { stage -> stage.writeText("inner", "x") }
    }

    @Test
    fun `move refuses a leftover's name as the new name`() {
        (rootPath / "present").writeRaw("present")

        assertRefusedToMake { writer.move("present", it) }
    }

    @Test
    fun `switchSymlink refuses a leftover's name`() = assertRefusedToMake(".current.$A_WRITE_ID.link") {
        writer.switchSymlink(it, Path("release"))
    }

    @Test
    fun `a child writer refuses a leftover's name`() = assertRefusedToMake(".pkg.$A_WRITE_ID.dir") {
        writer.writer(it)
    }

    @Test
    fun `a private child writer refuses a leftover's name`() = assertRefusedToMake(".pkg.$A_WRITE_ID.dir") {
        writer.privateWriter(it)
    }

    @Test
    fun `createDirectoryAtomically refuses a version named like a stage`() {
        val name = "tool/.2.0.$A_WRITE_ID.dir"

        val problem = RelativePathProblem.BadName(1, ".2.0.$A_WRITE_ID.dir", NameProblem.LeftoverName)
        val built = writer.createDirectoryAtomically(name) { stage -> stage.writeText("manifest.toml", "x") }
        assertEquals(Err(FileError.InvalidRelativePath(root, name, problem)), built)
        assertEquals(emptyList(), writer.leftovers(depth = Int.MAX_VALUE).assertSuccess().found)
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `an ordinary name is made as usual`() {
        assertEquals(Ok(WriteWay.Renamed), writer.writeText(".a.md.tmp", "x").way())
        assertEquals(Ok(Unit), writer.createDirectories("tool/2.0"))
        assertEquals(mapOf(".a.md.tmp" to "x", "tool" to "dir", "tool/2.0" to "dir"), rootPath.tree())
    }

    // --- calls on what is there

    @Test
    fun `a file with a leftover's name can be read looked up and appended to`() {
        (rootPath / temp).writeRaw("left")

        assertEquals(Ok("left"), writer.readText(temp))
        assertEquals(Ok("left".encodeToByteArray().toList()), writer.readBytes(temp).map { it.toList() })
        assertEquals(Ok(FileType.Regular), writer.info(temp).map { it.type })
        val appended = writer.openAppend(temp).flatMap { file -> file.use { it.append("!".encodeToByteArray()) } }
        assertEquals(Ok(Unit), appended)
        assertEquals("left!", (rootPath / temp).readRaw())
    }

    @Test
    fun `a file with a leftover's name can be deleted`() {
        (rootPath / temp).writeRaw("left")

        assertEquals(Ok(Unit), writer.delete(temp))
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `a folder with a stage's name can be deleted with all it holds`() {
        val stage = ".pkg.$A_WRITE_ID.dir"
        createDirectoriesRaw(rootPath / stage)
        (rootPath / stage / "inner").writeRaw("x")

        assertEquals(Ok(Unit), writer.deleteRecursively(stage))
        assertEquals(emptyMap(), rootPath.tree())
    }

    @Test
    fun `a file with a leftover's name can be moved to an ordinary name`() {
        (rootPath / temp).writeRaw("left")

        assertEquals(Ok(Unit), writer.move(temp, "a.md"))
        assertEquals(mapOf("a.md" to "left"), rootPath.tree())
    }
}
