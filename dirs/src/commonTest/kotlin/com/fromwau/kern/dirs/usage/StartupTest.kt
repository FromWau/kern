package com.fromwau.kern.dirs.usage

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.result.flatMap
import com.fromwau.kern.result.getOrElse
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class StartupTest {
    @Test
    fun `a relative XDG_CONFIG_HOME is reported and the config stays under HOME`() {
        val env = mapOf("HOME" to "/home/test", "XDG_CONFIG_HOME" to "cfg")

        val dirs = BaseDirs.fromXdg(env, accountHome = null)
            .flatMap { it.forApp("myapp") }
            .getOrElse { error("no folders for myapp: $it") }

        assertEquals(Path("/home/test/.config/myapp"), dirs.config)
        assertEquals(listOf("XDG_CONFIG_HOME"), dirs.ignored.map { it.name })
    }
}
