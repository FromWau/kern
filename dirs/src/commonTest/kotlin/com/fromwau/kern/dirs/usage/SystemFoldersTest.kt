package com.fromwau.kern.dirs.usage

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class SystemFoldersTest {
    private val env = mapOf("HOME" to "/home/test", "XDG_DATA_DIRS" to "/opt/x/share:/usr/share")

    @Test
    fun `an app searches its own config and then each system config folder`() {
        val dirs = BaseDirs.fromXdg(env, accountHome = null)
            .flatMap { it.forApp("myapp") }
            .getOrElse { error("no folders for myapp: $it") }

        val candidates = (listOf(dirs.config) + dirs.configDirs).map { Path(it, "defaults.toml") }

        val expected = listOf(Path("/home/test/.config/myapp/defaults.toml"), Path("/etc/xdg/myapp/defaults.toml"))
        assertEquals(expected, candidates)
    }

    @Test
    fun `a shared folder under every data root is searched from the base dirs`() {
        val base = BaseDirs.fromXdg(env, accountHome = null).getOrElse { error("no roots: $it") }

        val applications = (listOf(base.dataHome) + base.dataDirs).map { Path(it, "applications") }

        val expected = listOf(
            Path("/home/test/.local/share/applications"),
            Path("/opt/x/share/applications"),
            Path("/usr/share/applications"),
        )
        assertEquals(expected, applications)
    }
}
