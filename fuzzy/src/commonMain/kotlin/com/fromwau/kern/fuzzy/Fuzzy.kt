package com.fromwau.kern.fuzzy

import kotlin.math.abs

/**
 * How many single-character insertions, deletions, substitutions or swaps of two neighbouring characters turn
 * [from] into [to], each swap counting once and no character edited twice (the optimal string alignment
 * distance). Characters are UTF-16 code units compared exactly, case included, so a character outside the Basic
 * Multilingual Plane, such as an emoji, counts as two.
 *
 * ```kotlin
 * editDistance("kitten", "sitting") // 3
 * editDistance("ls", "sl")          // 1
 * ```
 */
public fun editDistance(
    from: String,
    to: String,
): Int {
    if (from == to) return 0
    if (from.isEmpty()) return to.length
    if (to.isEmpty()) return from.length
    var beforePrevious = IntArray(to.length + 1)
    var previous = IntArray(to.length + 1) { it }
    var current = IntArray(to.length + 1)
    for (i in 1..from.length) {
        current[0] = i
        for (j in 1..to.length) {
            val substitution = previous[j - 1] + if (from[i - 1] == to[j - 1]) 0 else 1
            var best = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            if (i > 1 && j > 1 && from[i - 1] == to[j - 2] && from[i - 2] == to[j - 1]) {
                best = minOf(best, beforePrevious[j - 2] + 1)
            }
            current[j] = best
        }
        val recycled = beforePrevious
        beforePrevious = previous
        previous = current
        current = recycled
    }
    return previous[to.length]
}

/**
 * The entry of [candidates] or [preferred] that [written] most likely meant, for a "did you mean" hint, or null
 * when none is close enough. Case is ignored, and the first rule that finds a name wins:
 * 1. a name that differs from [written] only in case. A [written] of one character gets nothing else;
 * 2. a name equal to [written] once case and the separators `_` and `-` are ignored, when [written] has at least
 *    two characters besides separators. A leading `-` counts too, so `settingNumber4999` finds
 *    `setting_number_4999`, `-dry-run` finds `--dry-run` and `-inf` finds `inf`;
 * 3. the only name that starts with [written], however long that name is, so `--o` finds `--output`. Dashes count
 *    like any other character here;
 * 4. the longest name that [written] starts with, of at least four characters, or three for a preferred name, so
 *    `warning` finds `warn` and `statuses` finds `status` over `stat`;
 * 5. the name fewest edits away by [editDistance]: one edit for a name of up to five characters, otherwise two
 *    edits or a third of its length, whichever is more, and always fewer edits than the longer of the two has
 *    characters. When [written] has three characters or fewer, a replaced character does not count as close, so
 *    `can` does not find `nan`.
 *
 * A name equal to [written] is never suggested, so check whether [written] is already known before asking, and
 * handle an end of options marker too, since `--` finds a single long option by rule 3 or a lone `-` by rule 5.
 * An empty [written] gets nothing, and it is not trimmed. Names that differ only in case or separators count as
 * one for rule 3. When names close enough by these rules tie, the one listed first in iteration order wins.
 *
 * ```kotlin
 * import com.fromwau.kern.fuzzy.didYouMean
 *
 * didYouMean("sqare", listOf("circle", "square"))                     // "square"
 * didYouMean("tcp", listOf("TCP", "udp"))                             // "TCP"
 * didYouMean("triangle", listOf("circle", "square"))                  // null
 * didYouMean("pu", listOf("pull", "push"), preferred = listOf("push")) // "push"
 * ```
 *
 * @param preferred the names the context expects, such as `true` and `false` where a boolean belongs. All the
 *   rules are tried on them first, and on [candidates] only when none finds a preferred name, so a preferred name
 *   found by rule 5 beats another that differs only in case. They count as candidates themselves, and a name in
 *   both counts once. To accept only preferred names, pass just those as [candidates].
 * @return the name as given, or null when none is close.
 */
public fun didYouMean(
    written: String,
    candidates: Collection<String>,
    preferred: Collection<String> = emptyList(),
): String? {
    val needle = written.lowercase()
    val expected = preferred.toSet()
    val (first, rest) = (preferred + candidates)
        .distinct()
        .filter { it != written }
        .map(::Name)
        .partition { it.text in expected }
    return closest(needle, first, shortestStart = 3) ?: closest(needle, rest, shortestStart = 4)
}

private class Name(val text: String) {
    val folded = text.lowercase()
}

private fun closest(
    needle: String,
    names: List<Name>,
    shortestStart: Int,
): String? {
    names.firstOrNull { it.folded == needle }?.let { return it.text }
    if (needle.length < 2) return null
    val bare = needle.withoutSeparators()
    if (bare.length >= 2) names.firstOrNull { it.folded.equalsWithoutSeparators(bare) }?.let { return it.text }
    names
        .filter { it.folded.startsWith(needle) }
        .distinctBy { it.folded.withoutSeparators() }
        .singleOrNull()
        ?.let { return it.text }
    names
        .filter { it.folded.length >= shortestStart && needle.startsWith(it.folded) }
        .maxByOrNull { it.folded.length }
        ?.let { return it.text }
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for (name in names) {
        val folded = name.folded
        val allowed = if (folded.length <= 5) 1 else maxOf(2, folded.length / 3)
        val limit = minOf(allowed, maxOf(needle.length, folded.length) - 1)
        // A tie goes to the candidate listed first, so a later one has to be strictly closer.
        val distance = editDistanceWithin(needle, folded, minOf(limit, bestDistance - 1)) ?: continue
        if (needle.length <= 3 && isReplacement(needle, folded)) continue
        best = name.text
        bestDistance = distance
    }
    return best
}

/** The [editDistance] from [from] to [to] when it is at most [limit], otherwise null. */
internal fun editDistanceWithin(
    from: String,
    to: String,
    limit: Int,
): Int? {
    if (abs(from.length - to.length) > limit) return null
    // Only cells within limit of the diagonal can stay within the limit; every other cell reads as too far.
    val tooFar = limit + 1
    var beforePrevious = IntArray(to.length + 1) { tooFar }
    var previous = IntArray(to.length + 1) { if (it <= limit) it else tooFar }
    var current = IntArray(to.length + 1) { tooFar }
    for (i in 1..from.length) {
        val first = maxOf(1, i - limit)
        val last = minOf(to.length, i + limit)
        current[0] = if (i <= limit) i else tooFar
        // The recycled row still holds an older row's value just left of the band.
        if (first > 1) current[first - 1] = tooFar
        var rowBest = current[0]
        for (j in first..last) {
            val substitution = previous[j - 1] + if (from[i - 1] == to[j - 1]) 0 else 1
            var best = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            if (i > 1 && j > 1 && from[i - 1] == to[j - 2] && from[i - 2] == to[j - 1]) {
                best = minOf(best, beforePrevious[j - 2] + 1)
            }
            current[j] = best
            rowBest = minOf(rowBest, best)
        }
        // Every cell builds on the two rows above it, so once a whole row is too far, so is the rest.
        if (rowBest > limit) return null
        val recycled = beforePrevious
        beforePrevious = previous
        previous = current
        current = recycled
    }
    return previous[to.length].takeIf { it <= limit }
}

private fun Char.isSeparator(): Boolean = this == '_' || this == '-'

private fun String.withoutSeparators(): String = filterNot { it.isSeparator() }

private fun String.equalsWithoutSeparators(bare: String): Boolean {
    var at = 0
    for (char in this) {
        if (char.isSeparator()) continue
        if (at == bare.length || char != bare[at]) return false
        at++
    }
    return at == bare.length
}

private fun isReplacement(
    a: String,
    b: String,
): Boolean = a.length == b.length && a.indices.count { a[it] != b[it] } == 1
