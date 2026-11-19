package com.fromwau.kern.dirs

import com.fromwau.kern.result.Err
import com.fromwau.kern.result.assertSuccess
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FromXdgTest {
    @Test
    fun `fromXdg lists a blank variable as Blank and keeps the root under HOME`() {
        val base = BaseDirs.fromXdg(mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to " "), accountHome = null)
            .assertSuccess()

        assertEquals(Path("/home/me", ".config"), base.configHome)
        assertEquals(listOf(IgnoredVariable("XDG_CONFIG_HOME", " ", IgnoredBecause.Blank)), base.ignored)
    }

    @Test
    fun `fromXdg lists an empty HOME as Blank and takes the account home`() {
        val base = BaseDirs.fromXdg(mapOf("HOME" to ""), accountHome = Path("/home/account")).assertSuccess()

        assertEquals(Path("/home/account"), base.home)
        assertEquals(listOf(IgnoredVariable("HOME", "", IgnoredBecause.Blank)), base.ignored)
    }

    @Test
    fun `fromXdg lists a blank TMPDIR and XDG_RUNTIME_DIR as Blank`() {
        val env = mapOf("HOME" to "/home/me", "TMPDIR" to "", "XDG_RUNTIME_DIR" to "\t")

        val base = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()

        assertEquals(Path("/tmp"), base.tempHome)
        assertNull(base.runtimeHome)
        val blank = listOf(
            IgnoredVariable("TMPDIR", "", IgnoredBecause.Blank),
            IgnoredVariable("XDG_RUNTIME_DIR", "\t", IgnoredBecause.Blank),
        )
        assertEquals(blank, base.ignored)
    }

    @Test
    fun `fromXdg with strict refuses a blank variable as Blank`() {
        val env = mapOf("HOME" to "/home/me", "XDG_STATE_HOME" to " ")
        val refused = BaseDirs.fromXdg(env, accountHome = null, strict = true)

        assertEquals(Err(DirsError.Blank("XDG_STATE_HOME", " ")), refused)
    }

    @Test
    fun `fromXdg with strict refuses a blank HOME as Blank though the account home would answer`() {
        val refused = BaseDirs.fromXdg(mapOf("HOME" to " "), accountHome = Path("/home/account"), strict = true)

        assertEquals(Err(DirsError.Blank("HOME", " ")), refused)
    }

    @Test
    fun `fromXdg lists no variable that is unset`() {
        val base = BaseDirs.fromXdg(mapOf("HOME" to "/home/me"), accountHome = null).assertSuccess()

        assertEquals(emptyList(), base.ignored)
    }

    @Test
    fun `fromXdg lists a relative variable as ignored and keeps the root under HOME`() {
        val base = BaseDirs.fromXdg(mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to "cfg"), accountHome = null)
            .assertSuccess()

        assertEquals(Path("/home/me", ".config"), base.configHome)
        assertEquals(listOf(IgnoredVariable("XDG_CONFIG_HOME", "cfg", IgnoredBecause.NotAbsolute)), base.ignored)
    }

    @Test
    fun `fromXdg with strict refuses a relative variable as NotAbsolute`() {
        val env = mapOf("HOME" to "/home/me", "XDG_CONFIG_HOME" to "cfg")

        assertEquals(
            Err(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg")),
            BaseDirs.fromXdg(env, accountHome = null, strict = true),
        )
    }

    @Test
    fun `fromXdg lists an XDG_RUNTIME_DIR naming no private folder as ignored and has no runtime root`() {
        val env = mapOf("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "/nowhere/run")

        val base = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()

        assertNull(base.runtimeHome)
        assertEquals(
            listOf(IgnoredVariable("XDG_RUNTIME_DIR", "/nowhere/run", IgnoredBecause.NotPrivateFolder)),
            base.ignored,
        )
    }

    @Test
    fun `fromXdg with strict refuses an XDG_RUNTIME_DIR naming no private folder as NotPrivate`() {
        val env = mapOf("HOME" to "/home/me", "XDG_RUNTIME_DIR" to "/nowhere/run")

        assertEquals(
            Err(DirsError.NotPrivate("XDG_RUNTIME_DIR", "/nowhere/run")),
            BaseDirs.fromXdg(env, accountHome = null, strict = true),
        )
    }

    @Test
    fun `fromXdg without HOME or an account home is UnableToResolve Home`() {
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), BaseDirs.fromXdg(emptyMap(), accountHome = null))
    }

    @Test
    fun `fromXdg without HOME takes the account home and a relative one counts as missing`() {
        val base = BaseDirs.fromXdg(emptyMap(), accountHome = Path("/home/account")).assertSuccess()

        assertEquals(Path("/home/account"), base.home)
        assertEquals(Path("/home/account", ".cache"), base.cacheHome)
        val relative = BaseDirs.fromXdg(emptyMap(), accountHome = Path("account"))
        assertEquals(Err(DirsError.UnableToResolve(DirKind.Home)), relative)
    }

    @Test
    fun `fromXdg takes TMPDIR as the temp root and slash tmp without it`() {
        val env = mapOf("HOME" to "/home/me")

        assertEquals(Path("/tmp"), BaseDirs.fromXdg(env, null).assertSuccess().tempHome)
        assertEquals(Path("/scratch"), BaseDirs.fromXdg(env + ("TMPDIR" to "/scratch"), null).assertSuccess().tempHome)
    }

    @Test
    fun `fromXdg without XDG_DATA_DIRS or XDG_CONFIG_DIRS takes the spec's defaults`() {
        val base = BaseDirs.fromXdg(mapOf("HOME" to "/home/me"), accountHome = null).assertSuccess()

        assertEquals(listOf(Path("/usr/local/share"), Path("/usr/share")), base.dataDirs)
        assertEquals(listOf(Path("/etc/xdg")), base.configDirs)
    }

    @Test
    fun `fromXdg splits XDG_DATA_DIRS and XDG_CONFIG_DIRS at colons in their order`() {
        val env = mapOf(
            "HOME" to "/home/me",
            "XDG_DATA_DIRS" to "/opt/share/:/usr/share",
            "XDG_CONFIG_DIRS" to "/etc/a:/etc/b",
        )

        val base = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()

        assertEquals(listOf(Path("/opt/share"), Path("/usr/share")), base.dataDirs)
        assertEquals(listOf(Path("/etc/a"), Path("/etc/b")), base.configDirs)
        assertEquals(emptyList(), base.ignored)
    }

    @Test
    fun `fromXdg lists each relative entry of a list variable with the entry as its value`() {
        val env = mapOf("HOME" to "/home/me", "XDG_DATA_DIRS" to "share:/usr/share:apps")

        val base = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()

        assertEquals(listOf(Path("/usr/share")), base.dataDirs)
        val relative = listOf(
            IgnoredVariable("XDG_DATA_DIRS", "share", IgnoredBecause.NotAbsolute),
            IgnoredVariable("XDG_DATA_DIRS", "apps", IgnoredBecause.NotAbsolute),
        )
        assertEquals(relative, base.ignored)
    }

    @Test
    fun `fromXdg with strict refuses the first relative entry of a list variable as NotAbsolute`() {
        val env = mapOf("HOME" to "/home/me", "XDG_DATA_DIRS" to "share:/usr/share:apps")

        assertEquals(
            Err(DirsError.NotAbsolute("XDG_DATA_DIRS", "share")),
            BaseDirs.fromXdg(env, accountHome = null, strict = true),
        )
    }

    @Test
    fun `fromXdg skips empty entries of a list variable without listing them`() {
        val env = mapOf("HOME" to "/home/me", "XDG_DATA_DIRS" to ":/opt/x/share::/usr/share:")

        val lenient = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()
        val strict = BaseDirs.fromXdg(env, accountHome = null, strict = true).assertSuccess()

        assertEquals(listOf(Path("/opt/x/share"), Path("/usr/share")), lenient.dataDirs)
        assertEquals(emptyList(), lenient.ignored)
        assertEquals(lenient, strict)
    }

    @Test
    fun `fromXdg keeps the first of a folder a list variable names twice`() {
        val env = mapOf("HOME" to "/home/me", "XDG_DATA_DIRS" to "/usr/share/:/usr/local/share:/usr/share")

        val base = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()

        assertEquals(listOf(Path("/usr/share"), Path("/usr/local/share")), base.dataDirs)
    }

    @Test
    fun `fromXdg skips whitespace-only entries of a list variable as it skips empty ones`() {
        val env = mapOf("HOME" to "/home/me", "XDG_DATA_DIRS" to "/usr/share: :\t")

        val lenient = BaseDirs.fromXdg(env, accountHome = null).assertSuccess()
        val strict = BaseDirs.fromXdg(env, accountHome = null, strict = true).assertSuccess()

        assertEquals(listOf(Path("/usr/share")), lenient.dataDirs)
        assertEquals(emptyList(), lenient.ignored)
        assertEquals(lenient, strict)
    }

    @Test
    fun `fromXdg takes the default for a list variable with no usable entry and lists what it dropped`() {
        val defaults = listOf(Path("/usr/local/share"), Path("/usr/share"))
        fun with(
            name: String,
            value: String,
        ) = BaseDirs.fromXdg(mapOf("HOME" to "/home/me", name to value), accountHome = null).assertSuccess()

        val relative = with("XDG_DATA_DIRS", "share")
        val onlyEmpty = with("XDG_DATA_DIRS", ":")
        val blank = with("XDG_DATA_DIRS", "   ")

        assertEquals(defaults, relative.dataDirs)
        assertEquals(listOf(IgnoredVariable("XDG_DATA_DIRS", "share", IgnoredBecause.NotAbsolute)), relative.ignored)
        assertEquals(defaults, onlyEmpty.dataDirs)
        assertEquals(emptyList(), onlyEmpty.ignored)
        assertEquals(defaults, blank.dataDirs)
        assertEquals(listOf(IgnoredVariable("XDG_DATA_DIRS", "   ", IgnoredBecause.Blank)), blank.ignored)
        assertEquals(listOf(Path("/etc/xdg")), with("XDG_CONFIG_DIRS", "xdg").configDirs)
    }
}
