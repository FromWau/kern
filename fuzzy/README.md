# fuzzy

Approximate string matching for Kotlin Multiplatform: which of a set of names someone most likely meant when they
typed something that is not one of them, and how far apart two words are.

**Targets:** JVM, Android, linuxX64, mingwX64, macosArm64, iosArm64, iosSimulatorArm64.

## Add to your build

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.frommhund.xyz/releases")
}

dependencies {
    implementation("com.fromwau.kern:fuzzy:$kernVersion")
}
```

## Suggest what was meant

```kotlin
public fun didYouMean(
    written: String,
    candidates: Collection<String>,
    preferred: Collection<String> = emptyList(),
): String?
```

```kotlin
import com.fromwau.kern.fuzzy.didYouMean

val commands = listOf("status", "commit", "push")
val written = "stauts"
if (written !in commands) {
    didYouMean(written, commands)?.let { println("unknown command '$written', did you mean '$it'?") }
}
```

A name equal to what was written is never suggested back, so check whether the word is already known first, as
above. The same goes for an end of options marker: `--` alone finds a single long option by rule 3, or a lone `-`
by the edit distance, so handle it before asking. A signed value such as `-inf` finds `inf` by rule 2, so accept
signed values before asking too. An empty word gets no suggestion, and words are not trimmed, so a space counts as
a character.

Otherwise case is ignored, and the first of these rules that finds a name wins:

1. a name that differs only in case. A word of one character gets nothing else, so `r` finds `R` but not `rm`;
2. a name equal to the word once case and the separators `_` and `-` are ignored, when the word has at least two
   characters besides separators. A leading `-` is a separator too. So `settingNumber4999` finds
   `setting_number_4999`, `maxconnections` finds `max_connections`, `-dry-run` finds `--dry-run` and `-inf` finds
   `inf`. By this rule `--n` does not find `-n`, but the edit distance still finds it when nothing else fits:
   `--n` over `--name` and `-n` gives `--name`, and over `-n` alone gives `-n`;
3. the only name that starts with what was written, however long that name is. Dashes count like any other
   character here, so `--o` finds `--output` and `un` finds `unix`. When several names fit, this rule finds none,
   and the edit distance may find a short option one dash away: `--f` over `--force`, `--format` and `-f` finds
   `-f`;
4. the longest name that what was written starts with, of at least four characters, or three for a preferred
   name. So `warning` finds `warn`, `information` finds `info` and `statuses` finds `status` over `stat`, while
   `logout` does not find `log` unless `log` is preferred;
5. the name fewest edits away: one edit for a name of up to five characters, otherwise two edits or a third of
   its length, whichever is more, and always fewer edits than the longer of the two has characters. In a word of
   three characters or fewer a replaced character does not count as close, so `can` is not taken for `nan`,
   while `an` (a character dropped) and `nna` (two swapped) are.

```kotlin
didYouMean("sqare", listOf("circle", "point", "square"))  // "square"
didYouMean("tcp", listOf("TCP", "udp"))                   // "TCP"
didYouMean("--o", listOf("--output", "--json"))           // "--output"
didYouMean("warning", listOf("debug", "info", "warn"))    // "warn"
didYouMean("triangle", listOf("circle", "square"))        // null
```

Names that differ only in case or separators count as one name for rule 3, and the one listed first is returned.
When two names close enough by these rules tie, the one listed first wins. A name that is not close does not
tie, so `bat` over `bad`, `bay` and `cat` gives nothing: each is one replaced letter from a three-letter word.
"Listed first" follows the collection's iteration order, so pass a `List` or a `LinkedHashSet` when the order
matters.

Case is folded one character at a time with `lowercase()`, so `İ` finds `i` while `i` does not find `İ`, and `ss`
does not find `ß`.

Each call compares the word with every name. Measured as the average of 1,000 calls on a warm JVM, a call over
10,000 names of up to 8 characters takes about 1.4 ms, and over 10,000 snake_case ids of 12 characters about 3 ms.

### Preferred names

When the context says which names to expect, pass them as `preferred`. All the rules are tried on the preferred
names first, and only when none of them finds a preferred name are they tried on the other names. So `pu` over
`push` and `pull` with `push` preferred gives `push`, and a preferred name found by the edit distance beats another
name that differs only in case: `didYouMean("Push", listOf("push"), preferred = listOf("puhs"))` gives `puhs`.
A short preferred name also beats a longer other name, as the last line below shows. Preferred names count as
candidates too, and a name in both lists counts once. If only the preferred names are acceptable, pass just those
as `candidates`.

```kotlin
val keywords = listOf("nan", "inf", "true", "false")
didYouMean("tru", keywords, preferred = listOf("true", "false"))  // "true"
didYouMean("t", keywords, preferred = listOf("true", "false"))    // null: one character is too little
didYouMean("logsx", listOf("logs"), preferred = listOf("log"))    // "log"
```

### Aliases

To suggest a command by one of its aliases too, pass the names and the aliases as candidates and map the result
back to the name it stands for. Do not pass the names as `preferred` to rank them over the aliases: a word that
starts with a preferred name of three characters finds it, so `logout` would find `log`.

```kotlin
val aliases = mapOf("rm" to "remove", "del" to "remove", "ls" to "list")
val meant = didYouMean(written, listOf("remove", "list") + aliases.keys)?.let { aliases[it] ?: it }
```

## Measure the distance

```kotlin
public fun editDistance(
    from: String,
    to: String,
): Int
```

```kotlin
import com.fromwau.kern.fuzzy.editDistance

editDistance("kitten", "sitting") // 3
editDistance("ls", "sl")          // 1
editDistance("tcp", "TCP")        // 3
```

`editDistance` counts single-character insertions, deletions, substitutions and swaps of two neighbouring
characters, a swap counting as one edit (the optimal string alignment distance), so the common typo `stauts` is
one edit from `status`. Unlike `didYouMean`, it is case-sensitive: it compares UTF-16 code units exactly, so an
emoji counts as two.
