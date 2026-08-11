package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun envOf(vars: Map<String, String>): (String) -> String? = { vars[it] }

/** The colour and width ladders, which are pure functions of the environment and so need no real terminal. */
class TerminalPolicyTest {

    @Test
    fun `NO_COLOR beats a forced colour request on a real tty`() {
        // NO_COLOR beats everything, even on a real tty with FORCE_COLOR set.
        val env = envOf(mapOf("NO_COLOR" to "1", "FORCE_COLOR" to "1"))
        assertFalse(ansiEnabled(isTty = true, env = env))
    }

    @Test
    fun `a forced colour request colours a pipe`() {
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "1"))))
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "1"))))
    }

    @Test
    fun `a dumb TERM disables colour even on a tty`() {
        assertFalse(ansiEnabled(isTty = true, env = envOf(mapOf("TERM" to "dumb"))))
    }

    @Test
    fun `colour follows the tty when nothing overrides it`() {
        assertTrue(ansiEnabled(isTty = true, env = envOf(emptyMap())))
        assertFalse(ansiEnabled(isTty = false, env = envOf(emptyMap())))
    }

    @Test
    fun `a force value of zero is an opt out rather than a force`() {
        // "0" is the opt-out value for both conventions, so it must not inject color into piped output.
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "0"))))
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "0"))))
    }

    @Test
    fun `any force value other than zero forces colour`() {
        // "1" is covered above; the rule is "anything but 0", so pin a value that is neither.
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "2"))))
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "true"))))
    }

    @Test
    fun `NO_COLOR beats CLICOLOR_FORCE`() {
        assertFalse(ansiEnabled(isTty = false, env = envOf(mapOf("NO_COLOR" to "1", "CLICOLOR_FORCE" to "1"))))
    }

    @Test
    fun `an empty NO_COLOR is treated as not set`() {
        // Per the NO_COLOR spec (no-color.org), the variable disables color only when present AND a
        // non-empty string; an empty value must be treated as not-set, so auto still follows the tty.
        assertTrue(ansiEnabled(isTty = true, env = envOf(mapOf("NO_COLOR" to ""))))
    }

    @Test
    fun `a non empty NO_COLOR disables colour on a tty`() {
        assertFalse(ansiEnabled(isTty = true, env = envOf(mapOf("NO_COLOR" to "1"))))
    }

    @Test
    fun `CLICOLOR of zero suppresses colour on a real tty`() {
        // Per the bixense CLICOLOR convention, plain CLICOLOR=0 suppresses color even on a real terminal.
        assertFalse(ansiEnabled(isTty = true, env = envOf(mapOf("CLICOLOR" to "0"))))
    }

    @Test
    fun `CLICOLOR_FORCE outranks a CLICOLOR of zero`() {
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("CLICOLOR_FORCE" to "1", "CLICOLOR" to "0"))))
    }

    @Test
    fun `a forced colour request ignores a handle that cannot render it`() {
        // On mingw, GetConsoleMode fails whenever stdout is redirected; an explicit force must still win,
        // since it bypasses the supported check entirely rather than being gated by it.
        assertTrue(ansiEnabled(isTty = false, env = envOf(mapOf("FORCE_COLOR" to "1")), supported = false))
    }

    @Test
    fun `auto detection respects a handle that cannot render colour`() {
        assertFalse(ansiEnabled(isTty = true, env = envOf(emptyMap()), supported = false))
    }

    @Test
    fun `COLUMNS sets the width when it is positive`() {
        assertEquals(120, resolveColumns(envOf(mapOf("COLUMNS" to "120"))))
    }

    @Test
    fun `an unset malformed or non positive COLUMNS falls back to eighty`() {
        assertEquals(80, resolveColumns(envOf(emptyMap())))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to ""))))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to "abc"))))
        assertEquals(80, resolveColumns(envOf(mapOf("COLUMNS" to "0"))))
    }

    @Test
    fun `the detected width is used when COLUMNS is unset`() {
        assertEquals(100, resolveColumns(envOf(emptyMap()), detected = 100))
    }

    @Test
    fun `COLUMNS overrides the detected width`() {
        assertEquals(120, resolveColumns(envOf(mapOf("COLUMNS" to "120")), detected = 100))
    }

    @Test
    fun `an absent or zero detected width falls back to eighty`() {
        assertEquals(80, resolveColumns(envOf(emptyMap()), detected = 0))
        assertEquals(80, resolveColumns(envOf(emptyMap()), detected = null))
    }
}
