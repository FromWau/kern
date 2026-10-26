package com.fromwau.kern.fuzzy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FuzzyTest {

    @Test
    fun `edit distance counts insertions deletions and substitutions`() {
        assertEquals(3, editDistance("kitten", "sitting"))
        assertEquals(0, editDistance("same", "same"))
        assertEquals(4, editDistance("", "four"))
        assertEquals(4, editDistance("four", ""))
        assertEquals(1, editDistance("ab", "ba"))
    }

    @Test
    fun `edit distance compares case exactly`() {
        assertEquals(3, editDistance("tcp", "TCP"))
    }

    @Test
    fun `a candidate that differs only in case is suggested`() {
        assertEquals("TCP", didYouMean("tcp", listOf("TCP", "udp")))
        assertEquals("fast", didYouMean("FAST", listOf("fast")))
        assertEquals("fast", didYouMean("Fast", listOf("fast")))
    }

    @Test
    fun `the only candidate that starts with the word is suggested however long it is`() {
        assertEquals("--help", didYouMean("--h", listOf("--help", "--json")))
        assertEquals("--output", didYouMean("--o", listOf("--output", "--json")))
        assertEquals("unix", didYouMean("un", listOf("TCP", "udp", "unix")))
        assertEquals("FAST", didYouMean("fa", listOf("FAST")))
    }

    @Test
    fun `a word's dashes count toward the start a candidate must share`() {
        assertNull(didYouMean("-o", listOf("--output", "--json")))
    }

    @Test
    fun `a single character is only matched by case`() {
        assertNull(didYouMean("r", listOf("run", "rm", "ps")))
        assertNull(didYouMean("s", listOf("status", "st", "commit")))
        assertEquals("r", didYouMean("R", listOf("r")))
        assertEquals("R", didYouMean("r", listOf("rm", "R")))
        assertNull(didYouMean("u", listOf("udp", "unix")))
        assertNull(didYouMean("t", listOf("true", "false")))
        assertEquals("false", didYouMean("fa", listOf("true", "false")))
        assertNull(didYouMean("v", listOf("-v", "--verbose")))
    }

    @Test
    fun `a lone high surrogate suggests nothing`() {
        assertNull(didYouMean("\uD83D", listOf("😀", "a")))
    }

    @Test
    fun `a candidate of four or more characters the word begins with is suggested`() {
        val levels = listOf("trace", "debug", "info", "warn", "error", "off")
        assertEquals("warn", didYouMean("warning", levels))
        assertEquals("info", didYouMean("information", levels))
        assertEquals("warn", didYouMean("WARNING", levels))
        assertEquals("WARN", didYouMean("warning", listOf("WARN", "warn")))
    }

    @Test
    fun `a candidate of three characters at the start of the word is not enough`() {
        assertNull(didYouMean("rmdir", listOf("rm", "ls")))
        assertEquals("status", didYouMean("statusx", listOf("st", "status")))
        assertNull(didYouMean("logout", listOf("log", "status", "commit")))
        assertNull(didYouMean("login", listOf("log", "status", "commit")))
        assertNull(didYouMean("infinity", listOf("inf", "nan")))
    }

    @Test
    fun `leading dashes do not count toward the length of a candidate at the start of the word`() {
        assertNull(didYouMean("--logout", listOf("--log")))
        assertEquals("--help", didYouMean("--helpful", listOf("--help")))
        assertEquals("--log", didYouMean("--logout", listOf("--log"), preferred = listOf("--log")))
        assertNull(didYouMean("logout", listOf("log")))
        assertNull(didYouMean("--all-x", listOf("--all")))
    }

    @Test
    fun `the longest candidate at the start of the word wins`() {
        assertEquals("inform", didYouMean("information", listOf("info", "inform")))
        assertEquals("inform", didYouMean("information", listOf("inform", "info")))
        assertEquals("logs", didYouMean("logsfoo", listOf("log", "logs")))
    }

    @Test
    fun `a preferred candidate at the start of the word wins over the others`() {
        assertEquals("true", didYouMean("trueish", listOf("tru", "true"), preferred = listOf("true")))
    }

    @Test
    fun `an end of options marker finds a single long option`() {
        assertEquals("--verbose", didYouMean("--", listOf("--verbose", "-q")))
    }

    @Test
    fun `case is folded by lowercase rather than full Unicode case folding`() {
        assertNull(didYouMean("i", listOf("\u0130")))
        assertEquals("i", didYouMean("\u0130", listOf("i")))
        assertNull(didYouMean("ss", listOf("\u00DF")))
    }

    @Test
    fun `aliases map back to the name they stand for`() {
        val aliases = mapOf("rm" to "remove", "del" to "remove", "ls" to "list")
        val names = listOf("remove", "list") + aliases.keys
        val meant = didYouMean("dl", names)?.let { aliases[it] ?: it }
        assertEquals("remove", meant)
    }

    @Test
    fun `an empty word suggests nothing`() {
        assertNull(didYouMean("", listOf("only")))
        assertNull(didYouMean("", listOf("one", "two")))
        assertNull(didYouMean("", emptyList()))
    }

    @Test
    fun `a word is not trimmed`() {
        assertEquals("st", didYouMean(" st", listOf("st")))
    }

    @Test
    fun `a near miss within reach is suggested`() {
        assertEquals("list", didYouMean("lst", listOf("list", "add")))
        assertEquals("config", didYouMean("cofnig", listOf("config", "ping")))
        assertEquals("build", didYouMean("biuld", listOf("build", "add")))
        assertEquals("udp", didYouMean("udpp", listOf("TCP", "udp")))
    }

    @Test
    fun `a candidate of up to five characters allows one edit`() {
        assertEquals("true", didYouMean("tru", listOf("true", "false")))
        assertNull(didYouMean("value", listOf("true", "false")))
        assertEquals("false", didYouMean("fasle", listOf("true", "false")))
        assertNull(didYouMean("sctp", listOf("TCP", "udp")))
        assertNull(didYouMean("tr", listOf("tcp", "true", "trap")))
    }

    @Test
    fun `a longer candidate allows an edit for every three characters`() {
        assertEquals("square", didYouMean("sqare", listOf("circle", "point", "square")))
        assertEquals("circle", didYouMean("circel", listOf("circle", "point", "square")))
        assertEquals("configuration", didYouMean("confgiuraton", listOf("configuration")))
    }

    @Test
    fun `a word that shares nothing with a candidate is not suggested`() {
        assertNull(didYouMean("xy", listOf("ls", "rm")))
        assertNull(didYouMean("ab", listOf("xy")))
    }

    @Test
    fun `nothing is suggested when no candidate is close`() {
        assertNull(didYouMean("triangle", listOf("circle", "point", "square")))
        assertNull(didYouMean("zzzzzzzz", listOf("list", "add")))
        assertNull(didYouMean("x", emptyList()))
    }

    @Test
    fun `the written word itself is never suggested`() {
        assertNull(didYouMean("list", listOf("list", "add")))
        assertNull(didYouMean("udp", listOf("udp")))
    }

    @Test
    fun `the nearest of several close candidates wins`() {
        assertEquals("push", didYouMean("pusk", listOf("pull", "push")))
    }

    @Test
    fun `swapping two neighbouring characters counts as one edit`() {
        assertEquals(1, editDistance("ls", "sl"))
        assertEquals(1, editDistance("stauts", "status"))
    }

    @Test
    fun `swapped characters are not edited again`() {
        assertEquals(3, editDistance("ca", "abc"))
    }

    @Test
    fun `edit distance counts UTF-16 code units`() {
        assertEquals(2, editDistance("a\uD83D\uDE00", "a"))
    }

    @Test
    fun `swapped letters are suggested`() {
        assertEquals("sl", didYouMean("ls", listOf("sl")))
        assertEquals("ba", didYouMean("ab", listOf("ba")))
        assertEquals("status", didYouMean("stauts", listOf("status", "stash")))
    }

    @Test
    fun `a candidate that differs only in case wins over a unique prefix`() {
        assertEquals("AB", didYouMean("ab", listOf("abc", "AB")))
    }

    @Test
    fun `candidates that differ only in case count once as a prefix`() {
        assertEquals("status", didYouMean("sta", listOf("status", "STATUS")))
    }

    @Test
    fun `a replaced character in a word of up to three characters is not close`() {
        assertNull(didYouMean("lx", listOf("ls", "rm")))
        assertNull(didYouMean("can", listOf("nan", "true")))
        assertEquals("nan", didYouMean("an", listOf("nan")))
        assertEquals("nan", didYouMean("nna", listOf("nan")))
        assertEquals("ls", didYouMean("sl", listOf("ls", "rm")))
        assertNull(didYouMean("bat", listOf("bad", "bay", "cat")))
    }

    @Test
    fun `the candidate listed first wins a tie`() {
        assertEquals("bast", didYouMean("bart", listOf("bast", "bant")))
        assertEquals("bant", didYouMean("bart", listOf("bant", "bast")))
        assertEquals("TCP", didYouMean("Tcp", listOf("TCP", "tcp")))
    }

    @Test
    fun `candidates may be any collection`() {
        assertEquals("square", didYouMean("sqare", setOf("circle", "square")))
    }

    @Test
    fun `a close preferred candidate wins over every other`() {
        val keywords = listOf("nan", "inf", "true", "false")
        assertEquals("true", didYouMean("tru", keywords, preferred = listOf("true", "false")))
        assertEquals("false", didYouMean("fasle", listOf("falsy", "false"), preferred = listOf("false")))
        assertEquals("inf", didYouMean("inff", listOf("info", "inf"), preferred = listOf("inf")))
    }

    @Test
    fun `preferred candidates still have to be close`() {
        assertEquals("nano", didYouMean("nanu", listOf("true", "false", "nano"), preferred = listOf("true", "false")))
        assertNull(didYouMean("t", listOf("true", "false"), preferred = listOf("true", "false")))
    }

    @Test
    fun `preferred candidates rank among themselves as any others`() {
        val preferred = listOf("false", "true")
        assertEquals("true", didYouMean("ture", listOf("false", "true"), preferred))
        assertEquals("bant", didYouMean("bart", listOf("bant", "bast"), preferred = listOf("bant", "bast")))
    }

    @Test
    fun `a preferred name counts as a candidate`() {
        assertEquals("true", didYouMean("tru", listOf("nan"), preferred = listOf("true")))
        assertEquals("true", didYouMean("tru", emptyList(), preferred = listOf("true")))
        assertNull(didYouMean("true", listOf("nan"), preferred = listOf("true")))
    }

    @Test
    fun `every rule tries the preferred names first`() {
        assertEquals("push", didYouMean("pu", listOf("pull", "push"), preferred = listOf("push")))
        assertEquals("dryrun", didYouMean("dry_run", listOf("dry-run", "dryrun"), preferred = listOf("dryrun")))
    }

    @Test
    fun `a candidate equal once case and separators are ignored is suggested`() {
        val settings = (0..4999).map { "setting_number_$it" }
        assertEquals("setting_number_4999", didYouMean("settingNumber4999", settings))
        assertEquals("setting_number_4999", didYouMean("settingNumber4999", settings.reversed()))
        assertEquals("setting_number_4999", didYouMean("setting-number-4999", settings))
        assertEquals("max_connections", didYouMean("maxconnections", listOf("max_connection", "max_connections")))
        assertEquals("--dry-run", didYouMean("-dry-run", listOf("--dry-run", "--dry")))
        assertEquals("dry-run", didYouMean("dry_run", listOf("dry-run", "dryrun")))
    }

    @Test
    fun `a candidate that differs only in case wins over one that differs in separators`() {
        assertEquals("dry_run", didYouMean("Dry_Run", listOf("dryrun", "dry_run")))
    }

    @Test
    fun `a word with one character besides separators is not matched by separators`() {
        assertEquals("--name", didYouMean("--n", listOf("--name", "-n")))
        assertEquals("-n", didYouMean("--n", listOf("-n")))
        // One dash fewer is one edit, so the edit distance still finds the short option.
        assertEquals("-f", didYouMean("--f", listOf("--force", "--format", "-f")))
        assertEquals("--output", didYouMean("--", listOf("-", "--output")))
        assertEquals("-", didYouMean("--", listOf("-")))
    }

    @Test
    fun `a preferred name of three characters at the start of the word is suggested`() {
        assertEquals("inf", didYouMean("infinity", listOf("inf", "nan"), preferred = listOf("inf", "nan")))
        assertEquals("infi", didYouMean("infinity", listOf("inf", "infi"), preferred = listOf("inf", "infi")))
        assertNull(didYouMean("logout", listOf("log", "status")))
    }

    @Test
    fun `a preferred name of three characters is suggested for any longer word that starts with it`() {
        val preferred = listOf("inf", "nan")
        assertEquals("inf", didYouMean("information", preferred, preferred))
        assertEquals("nan", didYouMean("nanosecond", preferred, preferred))
    }

    @Test
    fun `a name within edit reach wins over a shorter name the word starts with`() {
        assertEquals("hostname", didYouMean("hostame", listOf("host", "hostname")))
        assertEquals("database", didYouMean("datebase", listOf("date", "database")))
        assertEquals("hostname", didYouMean("hostame", listOf("hostname", "host")))
        assertEquals("status", didYouMean("statuses", listOf("stat", "status")))
        assertEquals("status", didYouMean("statuses", listOf("status", "stat")))
    }

    @Test
    fun `a leading dash is a separator`() {
        assertEquals("inf", didYouMean("-inf", listOf("inf", "nan")))
    }

    @Test
    fun `a short preferred name wins over a longer other name`() {
        assertEquals("log", didYouMean("logsx", listOf("logs"), preferred = listOf("log")))
    }

    @Test
    fun `preferring every name lets a word that starts with a three character name find it`() {
        val names = listOf("log", "status")
        val aliases = listOf("lg", "st")
        assertEquals("log", didYouMean("logout", names + aliases, preferred = names))
        assertNull(didYouMean("logout", names + aliases))
    }

    @Test
    fun `a preferred name found by distance wins over another name that differs only in case`() {
        assertEquals("puhs", didYouMean("Push", listOf("push"), preferred = listOf("puhs")))
    }

    @Test
    fun `candidates that differ only in separators count once as a prefix`() {
        assertEquals("set_up", didYouMean("set", listOf("set_up", "setup")))
    }
}
