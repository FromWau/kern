# fuzzy

Approximate string matching for Kotlin Multiplatform.

Someone types `stauts` and you have a command called `status`. A good tool says "did you mean 'status'?" instead of
just "unknown command". `fuzzy` finds which of a set of names someone most likely meant, and tells you how far apart
two words are.

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

- KMP consumers put it in `commonMain`.
- It depends on nothing but the Kotlin standard library.
- Needs what [every kern module needs](../README.md#requirements).

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

A name equal to what was written is never suggested back. So check first whether the word is already known, as
above.

Case is ignored. The first of these rules that finds a name wins:

1. A name that differs only in case.
   - A word of one character gets nothing else, so `r` finds `R` but not `rm`.
2. A name equal to the word once case and the separators `_` and `-`, even a leading one, are ignored. The word
   needs at least two characters besides separators.
   - So `settingNumber4999` finds `setting_number_4999`, `maxconnections` finds `max_connections`, `-dry-run` finds
     `--dry-run` and `-inf` finds `inf`.
   - By this rule `--n` does not find `-n`. Over `-n` alone the edit distance finds it. Over `--name` and `-n`,
     rule 3 picks `--name` first.
3. The only name that starts with what was written, however long that name is.
   - Dashes count like any other character here, so `--o` finds `--output` while `-o` does not.
   - When several names fit, this rule finds none. The edit distance may then find a short option one dash away:
     `--f` over `--force`, `--format` and `-f` finds `-f`.
4. The name fewest edits away.
   - A name of up to five characters may be one edit away.
   - A longer one may be two edits away or a third of its length, whichever is more.
   - Either way it must be fewer edits than the longer of the two has characters.
   - In a word of three characters or fewer, a replaced character does not count as close. So `can` is not taken
     for `nan`, while `an` (a character dropped) and `nna` (two swapped) are.
   - This rule comes before rule 5, so `hostame` finds `hostname` over `host`, and `datebase` finds `database`
     over `date`.
5. The longest name that what was written starts with. It needs at least four characters besides leading dashes, or
   three for a preferred name.
   - So `warning` finds `warn` and `information` finds `info`. Over `info` and `inform`, `information` finds
     `inform`.
   - `logout` does not find `log` unless `log` is preferred.
   - Likewise `--logout` does not find `--log` unless `--log` is preferred, while `--helpful` finds `--help`.

```kotlin
didYouMean("sqare", listOf("circle", "point", "square"))  // "square"
didYouMean("tcp", listOf("TCP", "udp"))                   // "TCP"
didYouMean("--o", listOf("--output", "--json"))           // "--output"
didYouMean("warning", listOf("debug", "info", "warn"))    // "warn"
didYouMean("triangle", listOf("circle", "square"))        // null
```

Each call compares the word with every name. Measured as the average of 1,000 calls on a warm JVM:

- Over 10,000 names of up to 8 characters, a call takes about 1.4 ms.
- Over 10,000 snake_case ids of 12 characters, it takes about 3 ms.

### Preferred names

Often the context says which names to expect. Pass them as `preferred`. All the rules are tried on the preferred
names first. Only when none of them finds a preferred name are they tried on the other names.

- So `pu` over `push` and `pull` with `push` preferred gives `push`.
- A preferred name found by the edit distance beats another name that differs only in case:
  `didYouMean("Push", listOf("push"), preferred = listOf("puhs"))` gives `puhs`.
- A short preferred name also beats a longer other name, as the last line below shows.
- Preferred names count as candidates too. A name in both lists counts once.
- If only the preferred names are acceptable, pass just those as `candidates`.

```kotlin
val keywords = listOf("nan", "inf", "true", "false")
didYouMean("tru", keywords, preferred = listOf("true", "false"))  // "true"
didYouMean("t", keywords, preferred = listOf("true", "false"))    // null: one character is too little
didYouMean("logsx", listOf("logs"), preferred = listOf("log"))    // "log"
```

### Aliases

To suggest a command by one of its aliases too, pass the names and the aliases as candidates. Then map the result
back to the name it stands for.

Do not pass the names as `preferred` to rank them over the aliases. A word that starts with a preferred name of
three characters finds it, so `logout` would find `log`.

```kotlin
val aliases = mapOf("rm" to "remove", "del" to "remove", "ls" to "list")
val meant = didYouMean(written, listOf("remove", "list") + aliases.keys)?.let { aliases[it] ?: it }
```

### Sharp edges

- **The end of options marker.** `--` alone finds a single long option by rule 3, or a lone `-` by the edit
  distance. Handle it before you ask.
- **Signed values.** A signed value such as `-inf` finds `inf` by rule 2. Accept signed values before you ask too.
- **Empty and padded words.** An empty word gets no suggestion. Words are not trimmed, so a space counts as a
  character.
- **Ties.** Names that differ only in case or separators count as one name for rule 3, and the one listed first is
  returned. When two names close enough by these rules tie, the one listed first wins.
- **Short preferred names.** A preferred name of three characters is suggested for any longer word that starts
  with it: with `inf` and `nan` preferred, `infinity` gives `inf`, and so does `information`, while `nanosecond`
  gives `nan`.
- **Not close is not a tie.** `bat` over `bad`, `bay` and `cat` gives nothing: each is one replaced letter from a
  three-letter word.
- **Order.** "Listed first" follows the collection's iteration order. Pass a `List` or a `LinkedHashSet` when the
  order matters.
- **Case folding.** Case is folded with `String.lowercase()`, not full Unicode folding. So `İ` finds `i` while `i`
  does not find `İ`, and `ss` does not find `ß`.

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

`editDistance` counts single-character insertions, deletions and substitutions. It also counts a swap of two
neighbouring characters as one edit. This is the optimal string alignment distance. So the common typo `stauts` is
one edit from `status`.

Unlike `didYouMean`, it is case-sensitive. It compares UTF-16 code units exactly, so an emoji counts as two.

## License

[Apache-2.0](../LICENSE).
