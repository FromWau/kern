package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals

class SymlinksTest {
    @Test
    fun `a verbatim drive path loses its prefix`() {
        assertEquals("C:\\Users\\me", withoutVerbatimPrefix("\\\\?\\C:\\Users\\me"))
    }

    @Test
    fun `a verbatim UNC path becomes a plain UNC path`() {
        assertEquals("\\\\server\\share\\x", withoutVerbatimPrefix("\\\\?\\UNC\\server\\share\\x"))
    }

    @Test
    fun `a path without the prefix is kept as it is`() {
        assertEquals("C:\\Users\\me", withoutVerbatimPrefix("C:\\Users\\me"))
        assertEquals("\\\\server\\share\\x", withoutVerbatimPrefix("\\\\server\\share\\x"))
    }
}
