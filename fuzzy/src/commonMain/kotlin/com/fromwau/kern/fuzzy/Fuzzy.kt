package com.fromwau.kern.fuzzy

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
 * The entry of [candidates] that [written] most likely meant, for a "did you mean" hint, or null when none is
 * close enough. Case is ignored throughout, and the first rule that finds a candidate wins:
 * 1. a candidate that differs from [written] only in case;
 * 2. the only candidate [written] is the start of, when [written] has at least two characters;
 * 3. the candidate fewest edits away by [editDistance]: one edit for a candidate of up to five characters,
 *    otherwise two edits or a third of its length, whichever is more, and always fewer edits than the longer of
 *    the two has characters. When [written] has three characters or fewer, a replaced character does not count
 *    as close, since too few characters are left to recognise the word by.
 *
 * Candidates in [preferred], the ones the context expects, such as `true` and `false` where a boolean belongs, are
 * looked at first: when one of them is close by these rules it wins over every other candidate, and none of them
 * is suggested when none is close. [written] itself is never suggested, since it is not unknown. When candidates
 * tie, the one listed first wins.
 *
 * ```kotlin
 * didYouMean("sqare", listOf("circle", "square"))                       // "square"
 * didYouMean("tcp", listOf("TCP", "udp"))                               // "TCP"
 * didYouMean("triangle", listOf("circle", "square"))                    // null
 * didYouMean("tru", listOf("nan", "true"), preferred = listOf("true"))  // "true"
 * ```
 */
public fun didYouMean(
    written: String,
    candidates: Collection<String>,
    preferred: Collection<String> = emptyList(),
): String? {
    val needle = written.lowercase()
    val others = candidates.filter { it != written }.map { it to it.lowercase() }
    val expected = preferred.toSet()
    val (first, rest) = others.partition { (candidate, _) -> candidate in expected }
    return closest(needle, first) ?: closest(needle, rest)
}

private fun closest(
    needle: String,
    others: List<Pair<String, String>>,
): String? {
    others.firstOrNull { (_, folded) -> folded == needle }?.let { return it.first }
    if (needle.length >= 2) {
        others
            .filter { (_, folded) -> folded.startsWith(needle) }
            .distinctBy { (_, folded) -> folded }
            .singleOrNull()
            ?.let { return it.first }
    }
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for ((candidate, folded) in others) {
        val allowed = if (folded.length <= 5) 1 else maxOf(2, folded.length / 3)
        val limit = minOf(allowed, maxOf(needle.length, folded.length) - 1)
        // More characters apart than the limit allows means more edits apart too, so skip the distance.
        if (kotlin.math.abs(needle.length - folded.length) > limit) continue
        val distance = editDistance(needle, folded)
        if (distance > limit || distance >= bestDistance) continue
        if (needle.length <= 3 && isReplacement(needle, folded)) continue
        best = candidate
        bestDistance = distance
    }
    return best
}

// One character replaced by another: the same length, and the two differ in exactly one place.
private fun isReplacement(
    a: String,
    b: String,
): Boolean = a.length == b.length && a.indices.count { a[it] != b[it] } == 1
