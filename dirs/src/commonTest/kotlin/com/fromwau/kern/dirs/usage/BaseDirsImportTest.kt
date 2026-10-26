package com.fromwau.kern.dirs.usage

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.IgnoredBecause
import com.fromwau.kern.dirs.IgnoredVariable
import com.fromwau.kern.dirs.toError
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class BaseDirsImportTest {
    @Test
    fun `under is reachable with only the BaseDirs import`() {
        assertEquals(Path("/scratch", "config"), BaseDirs.under(Path("/scratch")).configHome)
    }

    @Test
    fun `toError is reachable with its own import`() {
        val ignored = IgnoredVariable("XDG_CONFIG_HOME", "cfg", IgnoredBecause.NotAbsolute)

        assertEquals(DirsError.NotAbsolute("XDG_CONFIG_HOME", "cfg"), ignored.toError())
    }
}
