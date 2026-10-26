package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertError
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

@OptIn(InternalKernApi::class)
class SymlinkLoopTest {
    private val dir = newTempDir()
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    /** A symlink that points at itself. */
    private fun selfLoop(): Path = links.make(dir / "self", Path("self"))

    /** The first of two symlinks that point at each other. */
    private fun twoLinkLoop(): Path {
        links.make(dir / "second", Path("first"))
        return links.make(dir / "first", Path("second"))
    }

    private fun Result<*, FileError>.inaccessibleCause(): IoCause = assertError<FileError.Inaccessible>().cause

    @Test
    fun `fileType of a symlink loop is Inaccessible for Loop`() {
        assertEquals(IoCause.Loop, selfLoop().fileType().inaccessibleCause())
        assertEquals(IoCause.Loop, twoLinkLoop().fileType().inaccessibleCause())
    }

    @Test
    fun `readText of a symlink loop is Inaccessible for Loop`() {
        assertEquals(IoCause.Loop, selfLoop().readText().inaccessibleCause())
        assertEquals(IoCause.Loop, twoLinkLoop().readText().inaccessibleCause())
    }

    @Test
    fun `leftovers of a symlink loop are Inaccessible for Loop`() {
        assertEquals(IoCause.Loop, selfLoop().leftovers().inaccessibleCause())
        assertEquals(IoCause.Loop, twoLinkLoop().leftovers().inaccessibleCause())
    }

    @Test
    fun `a walk that follows links passes over a symlink pointing at itself as LinkLoop`() {
        val loop = selfLoop()

        for (symlinks in listOf(Symlinks.Anywhere, Symlinks.Inside)) {
            val walked = dir
                .walkTopDown(symlinks)
                .assertSuccess()
                .toList()

            assertEquals(listOf(Ok(WalkEntry.Skipped(loop, Path("self"), WalkSkip.LinkLoop))), walked, "$symlinks")
        }
    }

    @Test
    fun `a walk passes over both links of a two-link loop as LinkLoop`() {
        val _ = twoLinkLoop()

        val skipped = dir
            .walkTopDown(Symlinks.Anywhere)
            .assertSuccess()
            .map { it.assertSuccess() }
            .toList()

        val expected = listOf("first", "second").map { WalkEntry.Skipped(dir / it, Path(it), WalkSkip.LinkLoop) }
        assertEquals(expected, skipped)
    }

    @Test
    fun `a lookup below a symlink loop is Inaccessible for Loop`() {
        assertEquals(IoCause.Loop, (selfLoop() / "x").fileType().inaccessibleCause())
        assertEquals(IoCause.Loop, (twoLinkLoop() / "x").fileType().inaccessibleCause())
    }

    @Test
    fun `a symlink leading through a loop is Inaccessible for Loop`() {
        val _ = selfLoop()
        val through = links.make(dir / "through", Path("self/x"))
        val intoItself = links.make(dir / "intoItself", Path("intoItself/x"))

        assertEquals(IoCause.Loop, through.fileType().inaccessibleCause())
        assertEquals(IoCause.Loop, intoItself.fileType().inaccessibleCause())
    }

    @Test
    fun `creating a symlink below a symlink loop is WriteFailed for Loop`() {
        val self = (selfLoop() / "link").createSymlink(Path("x")).assertError<FileError.WriteFailed>()
        val two = (twoLinkLoop() / "link").createSymlink(Path("x")).assertError<FileError.WriteFailed>()

        assertEquals(listOf(IoCause.Loop, IoCause.Loop), listOf(self.cause, two.cause))
    }

    @Test
    fun `locking a symlink loop is LockFailed for Loop`() {
        val self = selfLoop().tryLock().assertError<FileError.LockFailed>()
        val two = twoLinkLoop().tryLock().assertError<FileError.LockFailed>()

        assertEquals(listOf(IoCause.Loop, IoCause.Loop), listOf(self.cause, two.cause))
    }

    /** A chain of [count] symlinks, each to the next, the last to a file; answers the first. */
    private fun chainOf(count: Int): Path {
        val file = (dir / "file").writeRaw("x")
        return (count downTo 1).fold(file) { next, n -> links.make(dir / "link$n", Path(next.name)) }
    }

    @Test
    fun `a chain of as many links as Linux follows ends at its file`() {
        assertEquals(dir / "file", chainOf(40).linkChainEnd())
    }

    @Test
    fun `a chain of one link more than Linux follows counts as going round`() {
        assertEquals(null, chainOf(41).linkChainEnd())
    }

    @Test
    fun `a lookup through as many links as Linux follows does not go round`() {
        assertEquals(false, chainOf(40).linksGoRound())
    }

    @Test
    fun `a lookup through one link more than Linux follows goes round`() {
        assertEquals(true, chainOf(41).linksGoRound())
    }
}
