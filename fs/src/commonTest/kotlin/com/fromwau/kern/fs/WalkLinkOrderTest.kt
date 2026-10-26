package com.fromwau.kern.fs

import com.fromwau.kern.result.Ok
import com.fromwau.kern.result.assertSuccess
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.files.Path

class WalkLinkOrderTest {
    private val dir = newTempDir()
    private val root = (dir / "root").also { createDirectoriesRaw(it) }
    private val links = TestLinks()

    @AfterTest
    fun cleanUp() {
        links.removeAll()
        dir.deleteTree()
    }

    @IgnorableReturnValue
    private fun Path.linkTo(target: Path): Path = links.make(this, target)

    private fun walked(symlinks: Symlinks): List<WalkEntry> = root
        .walkTopDown(symlinks)
        .assertSuccess()
        .map { it.assertSuccess() }
        .toList()

    private fun alreadyEntered(
        relative: String,
        first: String?,
    ): WalkEntry.Skipped {
        val why = WalkSkip.AlreadyEntered(first?.let(::Path))
        return WalkEntry.Skipped(root / relative, Path(relative), why)
    }

    private fun file(relative: String): WalkEntry.File =
        walkedFile(root / relative, Path(relative), size = 1, viaLink = false)

    private fun folder(relative: String): WalkEntry.Folder = WalkEntry.Folder(root / relative, Path(relative))

    /** `alias -> assets`, sorting before the folder it leads to, and `assets/x.css`. */
    private fun aliasBeforeAssets() {
        createDirectoriesRaw(root / "assets")
        (root / "assets" / "x.css").writeRaw("x")
        (root / "alias").linkTo(Path("assets"))
    }

    @Test
    fun `under Inside a link sorting before the folder it leads to is AlreadyEntered naming the folder's own path`() {
        aliasBeforeAssets()

        val expected = listOf(folder("assets"), file("assets/x.css"), alreadyEntered("alias", "assets"))
        assertEquals(expected, walked(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere a link sorting before the folder it leads to is AlreadyEntered naming the folder's own path`() {
        aliasBeforeAssets()

        val expected = listOf(folder("assets"), file("assets/x.css"), alreadyEntered("alias", "assets"))
        assertEquals(expected, walked(Symlinks.Anywhere))
    }

    @Test
    fun `a link sorting after the folder it leads to comes once the real tree is walked`() {
        createDirectoriesRaw(root / "assets")
        (root / "assets" / "x.css").writeRaw("x")
        (root / "zlink").linkTo(Path("assets"))
        (root / "z.txt").writeRaw("z")

        val expected = listOf(file("z.txt"), folder("assets"), file("assets/x.css"), alreadyEntered("zlink", "assets"))
        assertEquals(expected, walked(Symlinks.Inside))
    }

    @Test
    fun `a link to a folder deeper in another branch is AlreadyEntered naming that folder's own path`() {
        createDirectoriesRaw(root / "a")
        createDirectoriesRaw(root / "b" / "c")
        (root / "b" / "c" / "x").writeRaw("x")
        (root / "a" / "l").linkTo(Path("../b/c"))

        val expected = listOf(folder("a"), folder("b"), folder("b/c"), file("b/c/x"), alreadyEntered("a/l", "b/c"))
        assertEquals(expected, walked(Symlinks.Inside))
    }

    @Test
    fun `under Anywhere a link to a folder outside the root is walked under its own name after the real tree`() {
        val outside = dir / "outside"
        createDirectoriesRaw(outside)
        (outside / "f").writeRaw("f")
        (outside / "back").linkTo(Path("../root/sub"))
        createDirectoriesRaw(root / "sub")
        (root / "sub" / "x").writeRaw("x")
        (root / "out").linkTo(Path("../outside"))

        val expected = listOf(
            folder("sub"),
            file("sub/x"),
            folder("out"),
            walkedFile(root / "out" / "f", Path("out/f"), size = 1, viaLink = false),
            alreadyEntered("out/back", "sub"),
        )
        assertEquals(expected, walked(Symlinks.Anywhere))
    }

    @Test
    fun `a link to a folder it lies in is AlreadyEntered naming that folder and nothing is walked twice`() {
        createDirectoriesRaw(root / "sub")
        (root / "sub" / "x").writeRaw("x")
        (root / "sub" / "loop").linkTo(Path("../sub"))
        (root / "sub" / "up").linkTo(Path(".."))

        val expected = listOf(
            folder("sub"),
            file("sub/x"),
            alreadyEntered("sub/loop", "sub"),
            alreadyEntered("sub/up", null),
        )
        assertEquals(expected, walked(Symlinks.Inside))
    }

    @Test
    fun `a link to a folder the real tree does not reach is walked under the link's name`() {
        createDirectoriesRaw(root / "hidden" / "inner")
        (root / "hidden" / "inner" / "x").writeRaw("x")
        (root / "l").linkTo(Path("hidden/inner"))

        val walked = root
            .walkTopDown(Symlinks.Inside) { it.relative != Path("hidden") }
            .assertSuccess()
            .toList()

        val expected = listOf(
            WalkEntry.Skipped(root / "hidden", Path("hidden"), WalkSkip.Refused),
            folder("l"),
            walkedFile(root / "l" / "x", Path("l/x"), size = 1, viaLink = false),
        )
        assertEquals(expected.map { Ok(it) }, walked)
    }
}
