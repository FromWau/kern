package com.fromwau.kern.exec

import kotlin.test.Test
import kotlin.test.assertEquals

class JvmSupportTest {
    @Test
    fun `x86_64 and aarch64 Linux start children`() = assertEquals(
        listOf(null, null),
        listOf(unsupportedSystem("Linux", "amd64"), unsupportedSystem("Linux", "aarch64")),
    )

    @Test
    fun `other systems and CPUs say why they start nothing`() = assertEquals(
        listOf(
            "starting a child on the JVM needs Linux, and this JVM runs on Mac OS X",
            "starting a child on the JVM needs Linux, and this JVM runs on Windows 11",
            "starting a child on the JVM needs an x86_64 or aarch64 CPU, and this JVM runs on riscv64",
        ),
        listOf(
            unsupportedSystem("Mac OS X", "aarch64"),
            unsupportedSystem("Windows 11", "amd64"),
            unsupportedSystem("Linux", "riscv64"),
        ),
    )
}
