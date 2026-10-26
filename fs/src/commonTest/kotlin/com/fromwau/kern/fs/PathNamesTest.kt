package com.fromwau.kern.fs

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathNamesTest {

    @Test
    fun `div appends its child as written`() {
        assertEquals(Path("/etc", "app.toml"), Path("/etc") / "app.toml")
        assertEquals(Path("/etc/a"), (Path("/etc") / "a/b").parent)
        assertEquals(Path("/etc"), Path("/etc") / "")
        assertEquals(Path("/etc"), (Path("/etc") / "..").parent)
    }

    @Test
    fun `div with a String joins names as div with a Path does`() {
        assertEquals(Path("/etc", "x"), Path("/etc") / "/x")
        assertEquals(Path("/etc", "a", "b"), Path("/etc") / "a//b")
        assertEquals(Path("/etc", "a"), Path("/etc") / "a/")
        assertEquals(Path("/etc") / Path("a//b/"), Path("/etc") / "a//b/")
    }

    @Test
    fun `div with a Path appends its names below as div with a String does`() {
        assertEquals(Path("/etc", "app", "a.toml"), Path("/etc") / Path("app", "a.toml"))
        assertEquals(Path("/etc", "x", "y"), Path("/etc") / Path("/x/y"))
        assertEquals(Path("/etc"), (Path("/etc") / Path("..")).parent)
        assertEquals(Path("/etc") / "a/../b", Path("/etc") / Path("a/../b"))
    }

    @Test
    fun `the entry a path names folds a trailing dot and dot dot by name`() {
        assertEquals(Path("/a"), Path("/a/b/..").namedEntry())
        assertEquals(Path("/a"), Path("/a/b/c/./../..").namedEntry())
        assertEquals(Path("/a/b"), Path("/a/b/.").namedEntry())
        assertEquals(Path("/a/../b"), Path("/a/../b").namedEntry())
        assertEquals(Path("/"), Path("/a/../..").namedEntry())
        assertEquals(Path("."), Path("a/..").namedEntry())
        assertEquals(Path("."), Path(".").namedEntry())
        assertEquals(Path(".."), Path("..").namedEntry())
        assertEquals(Path("..", ".."), Path("a/../../..").namedEntry())
    }

    @Test
    fun `extension is everything after the last dot`() {
        assertEquals("mp3", Path("/music/song.mp3").extension)
        assertEquals("gz", Path("/tmp/archive.tar.gz").extension)
        assertEquals("kt", Path("/src/Foo.test.kt").extension)
    }

    @Test
    fun `extension is null without a dot`() {
        assertNull(Path("/docs/README").extension)
    }

    @Test
    fun `extension is null for a trailing dot`() {
        assertNull(Path("/docs/draft.").extension)
        assertNull(Path("/tmp/archive.tar.").extension)
    }

    @Test
    fun `a leading dot marks a hidden file rather than an extension`() {
        assertNull(Path("/home/me/.bashrc").extension)
        assertEquals("toml", Path("/home/me/.config.toml").extension)
    }

    @Test
    fun `nameWithoutExtension strips the last extension`() {
        assertEquals("song", Path("/music/song.mp3").nameWithoutExtension)
        assertEquals("archive.tar", Path("/tmp/archive.tar.gz").nameWithoutExtension)
        assertEquals("README", Path("/docs/README").nameWithoutExtension)
        assertEquals(".bashrc", Path("/home/me/.bashrc").nameWithoutExtension)
        assertEquals(".config", Path("/home/me/.config.toml").nameWithoutExtension)
    }

    @Test
    fun `expandTilde replaces a lone tilde with home`() {
        assertEquals(Path("/home/me"), Path("~").expandTilde(Path("/home/me")))
    }

    @Test
    fun `expandTilde expands a tilde before a separator`() {
        assertEquals(Path("/home/me/music"), Path("~/music").expandTilde(Path("/home/me")))
    }

    @Test
    fun `expandTilde leaves a named user tilde path alone`() {
        assertEquals(Path("~bob/x"), Path("~bob/x").expandTilde(Path("/home/me")))
    }

    @Test
    fun `expandTilde leaves an absolute path alone`() {
        assertEquals(Path("/etc/app.toml"), Path("/etc/app.toml").expandTilde(Path("/home/me")))
    }

    @Test
    fun `relativeTo gives the names below the base`() {
        assertEquals(Path("b", "c"), Path("/a/b/c").relativeTo(Path("/a")))
        assertEquals(Path("a"), Path("/a").relativeTo(Path("/")))
        assertEquals(Path("b"), Path("a/b").relativeTo(Path("a")))
    }

    @Test
    fun `relativeTo of the base itself is the empty path`() {
        assertEquals(Path(""), Path("/a/b").relativeTo(Path("/a/b")))
    }

    @Test
    fun `relativeTo compares whole names rather than characters`() {
        assertNull(Path("/a/bc").relativeTo(Path("/a/b")))
    }

    @Test
    fun `relativeTo of a path outside the base is null`() {
        assertNull(Path("/a").relativeTo(Path("/a/b")))
        assertNull(Path("/x/y").relativeTo(Path("/a")))
    }

    @Test
    fun `relativeTo of one absolute and one relative path is null`() {
        assertNull(Path("/a/b").relativeTo(Path("a")))
        assertNull(Path("a/b").relativeTo(Path("/a")))
    }

    @Test
    fun `relativeTo takes dot segments as written`() {
        assertNull(Path("/a/../b").relativeTo(Path("/b")))
        assertEquals(Path(".", "b"), Path("/a/./b").relativeTo(Path("/a")))
    }

    @Test
    fun `relativeTo a dot base takes only a path spelled from the dot`() {
        assertNull(Path("a").relativeTo(Path(".")))
        assertEquals(Path("a"), Path("./a").relativeTo(Path(".")))
    }

    @Test
    fun `relativeTo is null when the rest would climb out of the base`() {
        assertNull(Path("/a/../b").relativeTo(Path("/a")))
        assertNull(Path("a/b/../../c").relativeTo(Path("a")))
        assertNull(Path("/a/..").relativeTo(Path("/a")))
    }
}
