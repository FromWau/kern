package com.fromwau.kern.dirs.usage

import com.fromwau.kern.dirs.AppDirs
import com.fromwau.kern.dirs.BaseDirs
import com.fromwau.kern.dirs.BaseDirsFactory
import com.fromwau.kern.dirs.DirsError
import com.fromwau.kern.dirs.current
import com.fromwau.kern.result.Result
import com.fromwau.kern.result.assertSuccess
import com.fromwau.kern.result.flatMap
import kotlinx.io.files.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class CurrentImportJvmTest {
    @Test
    fun `the README's current line resolves the app's folders with the imports it names`() {
        val dirs: Result<AppDirs, DirsError> = BaseDirs.current().flatMap { it.forApp("myapp") }

        assertEquals(Path(BaseDirsFactory().create().assertSuccess().configHome, "myapp"), dirs.assertSuccess().config)
    }
}
