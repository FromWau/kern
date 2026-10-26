package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PermissionsTest {
    @Test
    fun `the owner accessors read the owner's own three bits`() {
        val readOnly = Permissions(0b100_111_111)
        val writeOnly = Permissions(0b010_000_000)
        val runOnly = Permissions(0b001_000_000)

        val owner = { it: Permissions -> listOf(it.ownerRead, it.ownerWrite, it.ownerExecute) }

        assertEquals(listOf(true, false, false), owner(readOnly))
        assertEquals(listOf(false, true, false), owner(writeOnly))
        assertEquals(listOf(false, false, true), owner(runOnly))
    }

    @Test
    fun `othersMayWrite is the group's or everyone's write bit`() {
        assertFalse(Permissions(0b111_101_101).othersMayWrite)
        assertTrue(Permissions(0b110_010_000).othersMayWrite)
        assertTrue(Permissions(0b110_000_010).othersMayWrite)
    }

    @Test
    fun `othersMayRead is the group's or everyone's read bit`() {
        assertFalse(Permissions(0b111_011_011).othersMayRead)
        assertTrue(Permissions(0b110_100_000).othersMayRead)
        assertTrue(Permissions(0b110_000_100).othersMayRead)
    }

    @Test
    fun `anyExecute is the owner's group's or everyone's execute bit`() {
        assertFalse(Permissions(0b110_110_110).anyExecute)
        assertFalse(Permissions(0b111_110_110_110).anyExecute)
        assertTrue(Permissions(0b001_000_000).anyExecute)
        assertTrue(Permissions(0b000_001_000).anyExecute)
        assertTrue(Permissions(0b000_000_001).anyExecute)
    }

    @Test
    fun `othersMayRead ignores the setuid setgid and sticky bits`() {
        assertFalse(Permissions(0b111_111_011_011).othersMayRead)
    }

    @Test
    fun `othersMayWrite ignores the setuid setgid and sticky bits`() {
        assertFalse(Permissions(0b111_111_101_101).othersMayWrite)
    }
}
