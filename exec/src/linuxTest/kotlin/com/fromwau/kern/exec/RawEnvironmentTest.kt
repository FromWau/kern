@file:OptIn(ExperimentalForeignApi::class)

package com.fromwau.kern.exec

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.set
import platform.posix.putenv
import platform.posix.unsetenv
import kotlin.test.Test
import kotlin.test.assertEquals

class RawEnvironmentTest {
    @Test
    fun `an inherited variable that is not UTF-8 reaches the child byte for byte`() {
        val entry = "KERN_RAW=".encodeToByteArray() + rawVariableBytes
        // putenv keeps the memory it is given, so it is never freed.
        val memory = nativeHeap.allocArray<ByteVar>(entry.size + 1)
        entry.forEachIndexed { index, byte -> memory[index] = byte }
        memory[entry.size] = 0
        check(putenv(memory) == 0)
        try {
            assertEquals(listOf("same", "same"), inheritingEnvironments.map(::rawVariableCheck))
        } finally {
            check(unsetenv("KERN_RAW") == 0)
        }
    }
}
