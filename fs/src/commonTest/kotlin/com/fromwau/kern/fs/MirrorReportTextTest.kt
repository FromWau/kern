package com.fromwau.kern.fs

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.io.IOException
import kotlinx.io.files.Path

class MirrorReportTextTest {
    private fun same(name: String): Mirrored = Mirrored(Path(name), Path(name))

    private val empty = MirrorReport(
        copied = emptyList(),
        unchanged = emptyList(),
        removed = emptyList(),
        held = emptyList(),
        passedOver = emptyList(),
        failures = emptyList(),
        notRemoved = emptyList(),
        removalsDeclined = emptyList(),
        sourceEmpty = false,
        unclean = emptyList(),
    )

    @Test
    fun `a clean report with nothing to say prints each count and clean`() {
        assertEquals(
            "MirrorReport(copied=0, unchanged=0, removed=0, held=0, passedOver=0, failures=0, notRemoved=0, " +
                "removalsDeclined=0, clean=true)",
            empty.toString(),
        )
    }

    @Test
    fun `a report prints each list that holds anything by relative path`() {
        val full = FileError.WriteFailed(Path("/dst/d.html"), IoCause.NoSpace, null)
        val report = empty.copy(
            copied = listOf(Mirrored(Path("a.md"), Path("a.html")), same("b.txt")),
            unchanged = listOf(same("c.txt")),
            removed = listOf(Path("old.html")),
            held = listOf(WalkEntry.Skipped(Path("/src/locked"), Path("locked"), WalkSkip.Refused)),
            passedOver = listOf(WalkEntry.Skipped(Path("/src/photos"), Path("photos"), WalkSkip.LeadsOutside)),
            failures = listOf(WalkFailure(Path("/src/d.md"), Path("d.md"), full)),
            notRemoved = listOf(FileError.WriteFailed(Path("/dst/stale"), IoCause.Denied, IOException("denied"))),
            removalsDeclined = listOf(Removal(Path("gone.html"), RemovalReason.Stale)),
            unclean = listOf(MirrorReport.Unclean.Kept(Removal(Path("gone.html"), RemovalReason.Stale))),
        )

        assertEquals(
            "MirrorReport(copied=2 [a.md -> a.html, b.txt], unchanged=1 [c.txt], removed=1 [old.html], " +
                "held=1 [locked: Refused], passedOver=1 [photos: LeadsOutside], failures=1 [d.md: WriteFailed], " +
                "notRemoved=1 [/dst/stale: WriteFailed], removalsDeclined=1 [gone.html], clean=false)",
            report.toString(),
        )
    }

    @Test
    fun `a report prints five entries of a long list and how many more it holds`() {
        val report = empty.copy(copied = (1..8).map { same("$it.txt") })

        assertEquals(
            "MirrorReport(copied=8 [1.txt, 2.txt, 3.txt, 4.txt, 5.txt, ...3 more], unchanged=0, removed=0, held=0, " +
                "passedOver=0, failures=0, notRemoved=0, removalsDeclined=0, clean=true)",
            report.toString(),
        )
    }

    @Test
    fun `a report cuts each name over 64 characters and gives its length in bytes`() {
        val long = "n".repeat(100)
        val cut = "n".repeat(64) + "... (100 bytes)"
        val report = empty.copy(
            copied = listOf(Mirrored(Path(long), Path("out", long))),
            removed = listOf(Path("sub", long)),
            notRemoved = listOf(FileError.WriteFailed(Path("/dst", long), IoCause.Denied, IOException("denied"))),
            removalsDeclined = listOf(Removal(Path(long), RemovalReason.Stale)),
        )

        assertEquals(
            "MirrorReport(copied=1 [$cut -> out/$cut], unchanged=0, removed=1 [sub/$cut], held=0, passedOver=0, " +
                "failures=0, notRemoved=1 [/dst/$cut: WriteFailed], removalsDeclined=1 [$cut], clean=true)",
            report.toString(),
        )
    }
}
