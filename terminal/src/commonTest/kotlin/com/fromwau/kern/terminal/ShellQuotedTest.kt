package com.fromwau.kern.terminal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A [name] as GNU coreutils 9.12 quoted it in a UTF-8 locale, generated from its output: [ls] by
 * `ls --quoting-style=shell-escape`, [message] by `cat` in its "No such file" message, [always] by
 * `ls --quoting-style=shell-escape-always`, and [escape] by `ls --quoting-style=escape`, given only for a name
 * without a space, the one character [escapeControls] leaves as it is where GNU escapes it.
 */
internal class Gnu(
    val name: String,
    val ls: String,
    val message: String,
    val always: String,
    val escape: String?,
)

internal val gnu = listOf(
        Gnu("plain.toml", ls = "plain.toml", message = "plain.toml", always = "'plain.toml'", escape = "plain.toml"),
        Gnu("a b", ls = "'a b'", message = "'a b'", always = "'a b'", escape = null),
        Gnu("it's", ls = "\"it's\"", message = "\"it's\"", always = "\"it's\"", escape = "it's"),
        Gnu("a\"b", ls = "'a\"b'", message = "'a\"b'", always = "'a\"b'", escape = "a\"b"),
        Gnu("a\$b", ls = "'a\$b'", message = "'a\$b'", always = "'a\$b'", escape = "a\$b"),
        Gnu("a`b", ls = "'a`b'", message = "'a`b'", always = "'a`b'", escape = "a`b"),
        Gnu("a\\b", ls = "'a\\b'", message = "'a\\b'", always = "'a\\b'", escape = "a\\\\b"),
        Gnu("a*b", ls = "'a*b'", message = "'a*b'", always = "'a*b'", escape = "a*b"),
        Gnu("a?b", ls = "'a?b'", message = "'a?b'", always = "'a?b'", escape = "a?b"),
        Gnu("a[b", ls = "'a[b'", message = "'a[b'", always = "'a[b'", escape = "a[b"),
        Gnu("a{b", ls = "a{b", message = "a{b", always = "'a{b'", escape = "a{b"),
        Gnu("a(b", ls = "'a(b'", message = "'a(b'", always = "'a(b'", escape = "a(b"),
        Gnu("a<b", ls = "'a<b'", message = "'a<b'", always = "'a<b'", escape = "a<b"),
        Gnu("a|b", ls = "'a|b'", message = "'a|b'", always = "'a|b'", escape = "a|b"),
        Gnu("a&b", ls = "'a&b'", message = "'a&b'", always = "'a&b'", escape = "a&b"),
        Gnu("a;b", ls = "'a;b'", message = "'a;b'", always = "'a;b'", escape = "a;b"),
        Gnu("#a", ls = "'#a'", message = "'#a'", always = "'#a'", escape = "#a"),
        Gnu("a#b", ls = "a#b", message = "a#b", always = "'a#b'", escape = "a#b"),
        Gnu("~a", ls = "'~a'", message = "'~a'", always = "'~a'", escape = "~a"),
        Gnu("a~b", ls = "a~b", message = "a~b", always = "'a~b'", escape = "a~b"),
        Gnu("a=b", ls = "'a=b'", message = "'a=b'", always = "'a=b'", escape = "a=b"),
        Gnu("=a", ls = "'=a'", message = "'=a'", always = "'=a'", escape = "=a"),
        Gnu("a!b", ls = "'a!b'", message = "'a!b'", always = "'a!b'", escape = "a!b"),
        Gnu("a%b", ls = "a%b", message = "a%b", always = "'a%b'", escape = "a%b"),
        Gnu("a^b", ls = "'a^b'", message = "'a^b'", always = "'a^b'", escape = "a^b"),
        Gnu("-a", ls = "-a", message = "-a", always = "'-a'", escape = "-a"),
        Gnu("a,b", ls = "a,b", message = "a,b", always = "'a,b'", escape = "a,b"),
        Gnu("a:b", ls = "a:b", message = "'a:b'", always = "'a:b'", escape = "a:b"),
        Gnu("a@b", ls = "a@b", message = "a@b", always = "'a@b'", escape = "a@b"),
        Gnu("a+b", ls = "a+b", message = "a+b", always = "'a+b'", escape = "a+b"),
        Gnu("a\u0009b", ls = "'a'\$'\\t''b'", message = "'a'\$'\\t''b'", always = "'a'\$'\\t''b'", escape = "a\\tb"),
        Gnu("a\u000Ab", ls = "'a'\$'\\n''b'", message = "'a'\$'\\n''b'", always = "'a'\$'\\n''b'", escape = "a\\nb"),
        Gnu(
            "a\u001Bb",
            ls = "'a'\$'\\033''b'",
            message = "'a'\$'\\033''b'",
            always = "'a'\$'\\033''b'",
            escape = "a\\033b",
        ),
        Gnu(
            "a\u007Fb",
            ls = "'a'\$'\\177''b'",
            message = "'a'\$'\\177''b'",
            always = "'a'\$'\\177''b'",
            escape = "a\\177b",
        ),
        Gnu(
            "a\u0001b",
            ls = "'a'\$'\\001''b'",
            message = "'a'\$'\\001''b'",
            always = "'a'\$'\\001''b'",
            escape = "a\\001b",
        ),
        Gnu("a\u000Db", ls = "'a'\$'\\r''b'", message = "'a'\$'\\r''b'", always = "'a'\$'\\r''b'", escape = "a\\rb"),
        Gnu("caf\u00E9", ls = "caf\u00E9", message = "caf\u00E9", always = "'caf\u00E9'", escape = "caf\u00E9"),
        Gnu("a\u00A0b", ls = "a\u00A0b", message = "a\u00A0b", always = "'a\u00A0b'", escape = "a\u00A0b"),
        Gnu("it's \$x", ls = "'it'\\''s \$x'", message = "'it'\\''s \$x'", always = "'it'\\''s \$x'", escape = null),
        Gnu("it's a", ls = "\"it's a\"", message = "\"it's a\"", always = "\"it's a\"", escape = null),
        Gnu("'", ls = "\"'\"", message = "\"'\"", always = "\"'\"", escape = "'"),
        Gnu("''", ls = "\"''\"", message = "\"''\"", always = "\"''\"", escape = "''"),
        Gnu("a'b\"c", ls = "'a'\\''b\"c'", message = "'a'\\''b\"c'", always = "'a'\\''b\"c'", escape = "a'b\"c"),
        Gnu(
            "it's\u000A",
            ls = "'it'\\''s'\$'\\n'",
            message = "'it'\\''s'\$'\\n'",
            always = "'it'\\''s'\$'\\n'",
            escape = "it's\\n",
        ),
        Gnu("{", ls = "'{'", message = "'{'", always = "'{'", escape = "{"),
        Gnu("}", ls = "'}'", message = "'}'", always = "'}'", escape = "}"),
        Gnu("a]b", ls = "a]b", message = "a]b", always = "'a]b'", escape = "a]b"),
        Gnu("a}b", ls = "a}b", message = "a}b", always = "'a}b'", escape = "a}b"),
        Gnu("{}", ls = "{}", message = "{}", always = "'{}'", escape = "{}"),
        Gnu("\u000Aa", ls = "''\$'\\n''a'", message = "''\$'\\n''a'", always = "''\$'\\n''a'", escape = "\\na"),
        Gnu("a\u000A", ls = "'a'\$'\\n'", message = "'a'\$'\\n'", always = "'a'\$'\\n'", escape = "a\\n"),
        Gnu("\u000A", ls = "''\$'\\n'", message = "''\$'\\n'", always = "''\$'\\n'", escape = "\\n"),
        Gnu("\u000A\u000A", ls = "''\$'\\n\\n'", message = "''\$'\\n\\n'", always = "''\$'\\n\\n'", escape = "\\n\\n"),
        Gnu(
            "a\u000A\u0009b",
            ls = "'a'\$'\\n\\t''b'",
            message = "'a'\$'\\n\\t''b'",
            always = "'a'\$'\\n\\t''b'",
            escape = "a\\n\\tb",
        ),
        Gnu(
            "\u0007\u0008\u000C\u000B",
            ls = "''\$'\\a\\b\\f\\v'",
            message = "''\$'\\a\\b\\f\\v'",
            always = "''\$'\\a\\b\\f\\v'",
            escape = "\\a\\b\\f\\v",
        ),
        Gnu(
            "a\u0085b",
            ls = "'a'\$'\\302\\205''b'",
            message = "'a'\$'\\302\\205''b'",
            always = "'a'\$'\\302\\205''b'",
            escape = "a\\302\\205b",
        ),
        Gnu(
            "a\u009Bb",
            ls = "'a'\$'\\302\\233''b'",
            message = "'a'\$'\\302\\233''b'",
            always = "'a'\$'\\302\\233''b'",
            escape = "a\\302\\233b",
        ),
        Gnu("a\u200Bb", ls = "a\u200Bb", message = "a\u200Bb", always = "'a\u200Bb'", escape = "a\u200Bb"),
        Gnu(
            "a\u2028b",
            ls = "'a'\$'\\342\\200\\250''b'",
            message = "'a'\$'\\342\\200\\250''b'",
            always = "'a'\$'\\342\\200\\250''b'",
            escape = "a\\342\\200\\250b",
        ),
        Gnu("a\uFEFFb", ls = "a\uFEFFb", message = "a\uFEFFb", always = "'a\uFEFFb'", escape = "a\uFEFFb"),
        Gnu("e\u0301", ls = "e\u0301", message = "e\u0301", always = "'e\u0301'", escape = "e\u0301"),
        Gnu(
            "\uD83D\uDE00",
            ls = "\uD83D\uDE00",
            message = "\uD83D\uDE00",
            always = "'\uD83D\uDE00'",
            escape = "\uD83D\uDE00",
        ),
        Gnu(
            "a'\u001Bb",
            ls = "'a'\\'''\$'\\033''b'",
            message = "'a'\\'''\$'\\033''b'",
            always = "'a'\\'''\$'\\033''b'",
            escape = "a'\\033b",
        ),
        Gnu("!a", ls = "'!a'", message = "'!a'", always = "'!a'", escape = "!a"),
        Gnu("a.b", ls = "a.b", message = "a.b", always = "'a.b'", escape = "a.b"),
        Gnu(".a", ls = ".a", message = ".a", always = "'.a'", escape = ".a"),
        Gnu("a]", ls = "a]", message = "a]", always = "'a]'", escape = "a]"),
        Gnu("]a", ls = "]a", message = "]a", always = "']a'", escape = "]a"),
        Gnu("%a", ls = "%a", message = "%a", always = "'%a'", escape = "%a"),
        Gnu("a?", ls = "'a?'", message = "'a?'", always = "'a?'", escape = "a?"),
        Gnu("it's!", ls = "'it'\\''s!'", message = "'it'\\''s!'", always = "'it'\\''s!'", escape = "it's!"),
        Gnu("it's\\", ls = "'it'\\''s\\'", message = "'it'\\''s\\'", always = "'it'\\''s\\'", escape = "it's\\\\"),
        Gnu("it's`", ls = "'it'\\''s`'", message = "'it'\\''s`'", always = "'it'\\''s`'", escape = "it's`"),
        Gnu("it's*", ls = "'it'\\''s*'", message = "'it'\\''s*'", always = "'it'\\''s*'", escape = "it's*"),
        Gnu("it's\"", ls = "'it'\\''s\"'", message = "'it'\\''s\"'", always = "'it'\\''s\"'", escape = "it's\""),
        Gnu("it's=", ls = "'it'\\''s='", message = "'it'\\''s='", always = "'it'\\''s='", escape = "it's="),
        Gnu("it's#", ls = "'it'\\''s#'", message = "'it'\\''s#'", always = "'it'\\''s#'", escape = "it's#"),
        Gnu("#it's", ls = "\"#it's\"", message = "\"#it's\"", always = "\"#it's\"", escape = "#it's"),
        Gnu("it's{", ls = "'it'\\''s{'", message = "'it'\\''s{'", always = "'it'\\''s{'", escape = "it's{"),
        Gnu("it's:", ls = "\"it's:\"", message = "\"it's:\"", always = "\"it's:\"", escape = "it's:"),
        Gnu("it's;", ls = "'it'\\''s;'", message = "'it'\\''s;'", always = "'it'\\''s;'", escape = "it's;"),
        Gnu("it's^", ls = "'it'\\''s^'", message = "'it'\\''s^'", always = "'it'\\''s^'", escape = "it's^"),
        Gnu(
            "it's\u0085",
            ls = "'it'\\''s'\$'\\302\\205'",
            message = "'it'\\''s'\$'\\302\\205'",
            always = "'it'\\''s'\$'\\302\\205'",
            escape = "it's\\302\\205",
        ),
        Gnu(
            "it's \u00E9",
            ls = "\"it's \u00E9\"",
            message = "\"it's \u00E9\"",
            always = "\"it's \u00E9\"",
            escape = null,
        ),
        Gnu("~it's", ls = "\"~it's\"", message = "\"~it's\"", always = "\"~it's\"", escape = "~it's"),
        Gnu("it's~", ls = "'it'\\''s~'", message = "'it'\\''s~'", always = "'it'\\''s~'", escape = "it's~"),
        Gnu("it's&", ls = "'it'\\''s&'", message = "'it'\\''s&'", always = "'it'\\''s&'", escape = "it's&"),
        Gnu("it's(", ls = "'it'\\''s('", message = "'it'\\''s('", always = "'it'\\''s('", escape = "it's("),
        Gnu("it's[", ls = "'it'\\''s['", message = "'it'\\''s['", always = "'it'\\''s['", escape = "it's["),
        Gnu("it's|", ls = "'it'\\''s|'", message = "'it'\\''s|'", always = "'it'\\''s|'", escape = "it's|"),
        Gnu("it's<", ls = "'it'\\''s<'", message = "'it'\\''s<'", always = "'it'\\''s<'", escape = "it's<"),
        Gnu(
            "it's\uD83D\uDE00",
            ls = "\"it's\uD83D\uDE00\"",
            message = "\"it's\uD83D\uDE00\"",
            always = "\"it's\uD83D\uDE00\"",
            escape = "it's\uD83D\uDE00",
        ),
        Gnu("it's@", ls = "\"it's@\"", message = "\"it's@\"", always = "\"it's@\"", escape = "it's@"),
        Gnu("it's}", ls = "'it'\\''s}'", message = "'it'\\''s}'", always = "'it'\\''s}'", escape = "it's}"),
        Gnu("it's/b", ls = "\"it's/b\"", message = "\"it's/b\"", always = "\"it's/b\"", escape = "it's/b"),
        Gnu("\u000A'", ls = "''\$'\\n'\\'''", message = "''\$'\\n'\\'''", always = "''\$'\\n'\\'''", escape = "\\n'"),
        Gnu(
            "a\u000A'b",
            ls = "'a'\$'\\n'\\''b'",
            message = "'a'\$'\\n'\\''b'",
            always = "'a'\$'\\n'\\''b'",
            escape = "a\\n'b",
        ),
        Gnu("it's?", ls = "'it'\\''s?'", message = "'it'\\''s?'", always = "'it'\\''s?'", escape = "it's?"),
        Gnu("'\u000A", ls = "''\\'''\$'\\n'", message = "''\\'''\$'\\n'", always = "''\\'''\$'\\n'", escape = "'\\n"),
        Gnu("a/b", ls = "a/b", message = "a/b", always = "'a/b'", escape = "a/b"),
        Gnu(
            "\u0378",
            ls = "''\$'\\315\\270'",
            message = "''\$'\\315\\270'",
            always = "''\$'\\315\\270'",
            escape = "\\315\\270",
        ),
        Gnu(
            "a\uFFFEb",
            ls = "'a'\$'\\357\\277\\276''b'",
            message = "'a'\$'\\357\\277\\276''b'",
            always = "'a'\$'\\357\\277\\276''b'",
            escape = "a\\357\\277\\276b",
        ),
        Gnu(
            "\uFDD0",
            ls = "''\$'\\357\\267\\220'",
            message = "''\$'\\357\\267\\220'",
            always = "''\$'\\357\\267\\220'",
            escape = "\\357\\267\\220",
        ),
        Gnu(
            "\uFFFF",
            ls = "''\$'\\357\\277\\277'",
            message = "''\$'\\357\\277\\277'",
            always = "''\$'\\357\\277\\277'",
            escape = "\\357\\277\\277",
        ),
        Gnu(
            "\uDBFF\uDFFF",
            ls = "''\$'\\364\\217\\277\\277'",
            message = "''\$'\\364\\217\\277\\277'",
            always = "''\$'\\364\\217\\277\\277'",
            escape = "\\364\\217\\277\\277",
        ),
        Gnu(
            "x\uD83F\uDFFEy",
            ls = "'x'\$'\\360\\237\\277\\276''y'",
            message = "'x'\$'\\360\\237\\277\\276''y'",
            always = "'x'\$'\\360\\237\\277\\276''y'",
            escape = "x\\360\\237\\277\\276y",
        ),
        Gnu(
            "it's\u0378",
            ls = "'it'\\''s'\$'\\315\\270'",
            message = "'it'\\''s'\$'\\315\\270'",
            always = "'it'\\''s'\$'\\315\\270'",
            escape = "it's\\315\\270",
        ),
        Gnu("a\\x1Bb", ls = "'a\\x1Bb'", message = "'a\\x1Bb'", always = "'a\\x1Bb'", escape = "a\\\\x1Bb"),
        Gnu(
            "back\\slash",
            ls = "'back\\slash'",
            message = "'back\\slash'",
            always = "'back\\slash'",
            escape = "back\\\\slash",
        ),
        Gnu("a\u00ADb", ls = "a\u00ADb", message = "a\u00ADb", always = "'a\u00ADb'", escape = "a\u00ADb"),
)

class ShellQuotedTest {

    @Test
    fun `a name is quoted as GNU ls quotes it`() {
        for (row in gnu) assertEquals(row.ls, row.name.shellQuoted(), row.name)
    }

    @Test
    fun `a name in a message is quoted as GNU cat quotes it`() {
        for (row in gnu) assertEquals(row.message, row.name.shellQuoted(forMessage = true), row.name)
    }

    @Test
    fun `a name quoted always is quoted as GNU ls shell-escape-always quotes it`() {
        for (row in gnu) assertEquals(row.always, row.name.shellQuoted(always = true), row.name)
    }

    @Test
    fun `an empty name is two single quotes`() {
        assertEquals("''", "".shellQuoted())
    }

    @Test
    fun `a name that needs no quoting comes back as it is`() {
        assertEquals("notes.toml", "notes.toml".shellQuoted())
    }
}
