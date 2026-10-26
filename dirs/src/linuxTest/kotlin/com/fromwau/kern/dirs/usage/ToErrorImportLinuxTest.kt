package com.fromwau.kern.dirs.usage

import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.BaseDirsFactory
import com.fromwau.kern.dirs.toError
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.getOrElse
import kotlin.test.Test
import kotlin.test.assertEquals

class ToErrorImportLinuxTest {
    /** The README's check for an app that never uses runtime, with the imports it names. */
    private fun relativeVariableCheck(): BaseDirs {
        val base = BaseDirsFactory().create().getOrElse { error("no folders for this app: $it") }
        val refused = base.ignored.firstOrNull { it.name != "XDG_RUNTIME_DIR" }?.toError()
        if (refused != null) error("no folders for this app: $refused")
        return base
    }

    @Test
    fun `the README's relative variable check compiles with the imports it names`() {
        val base = BaseDirsFactory().create().assertSuccess()
        if (base.ignored.any { it.name != "XDG_RUNTIME_DIR" }) return

        assertEquals(base, relativeVariableCheck())
    }
}
