# fuzzy

Approximate string matching for Kotlin Multiplatform: how far apart two words are, and which of a set of names
someone most likely meant when they typed something that is not one of them.

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
didYouMean("sqare", listOf("circle", "point", "square"))  // "square"
didYouMean("tcp", listOf("TCP", "udp"))                   // "TCP"
didYouMean("un", listOf("udp", "unix"))                   // "unix"
didYouMean("triangle", listOf("circle", "square"))        // null
```

Case is ignored, and the first of these that finds a name wins:

1. a name that differs only in case;
2. the only name what was written is the start of;
3. the name fewest edits away, within two edits or a third of its length, whichever is more, and with fewer
   edits than the longer of the two has characters.

What was written is never suggested back, nothing is suggested when no name is close, and when two names are
equally close the one listed first wins.

## Measure the distance

```kotlin
editDistance("kitten", "sitting") // 3
editDistance("ls", "sl")          // 1
```

`editDistance` counts single-character insertions, deletions, substitutions and swaps of two neighbouring
characters, a swap counting as one edit (the optimal string alignment distance), so the common typo `stauts` is
one edit from `status`. It compares UTF-16 code units exactly, case included, so an emoji counts as two.
