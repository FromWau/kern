package com.fromwau.kern.dirs

import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PathNamesTest {

    @Test
    fun `div appends one segment`() {
        assertEquals(Path("/etc", "app.toml"), Path("/etc") / "app.toml")
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
}
