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
 * 2. the only candidate [written] is the start of, however short [written] is;
 * 3. the candidate fewest edits away by [editDistance], within two edits or a third of its length, whichever is
 *    more, and with fewer edits than the longer of the two has characters.
 *
 * [written] itself is never suggested, since it is not unknown. When candidates tie, the one listed first wins.
 *
 * ```kotlin
 * didYouMean("sqare", listOf("circle", "square"))    // "square"
 * didYouMean("tcp", listOf("TCP", "udp"))            // "TCP"
 * didYouMean("triangle", listOf("circle", "square")) // null
 * ```
 */
public fun didYouMean(
    written: String,
    candidates: Collection<String>,
): String? {
    val needle = written.lowercase()
    val others = candidates.filter { it != written }.map { it to it.lowercase() }
    others.firstOrNull { (_, folded) -> folded == needle }?.let { return it.first }
    others
        .filter { (_, folded) -> folded.startsWith(needle) }
        .distinctBy { (_, folded) -> folded }
        .singleOrNull()
        ?.let { return it.first }
    var best: String? = null
    var bestDistance = Int.MAX_VALUE
    for ((candidate, folded) in others) {
        val limit = minOf(maxOf(2, candidate.length / 3), maxOf(needle.length, folded.length) - 1)
        // More characters apart than the limit allows means more edits apart too, so skip the distance.
        if (kotlin.math.abs(needle.length - folded.length) > limit) continue
        val distance = editDistance(needle, folded)
        if (distance <= limit && distance < bestDistance) {
            best = candidate
            bestDistance = distance
        }
    }
    return best
}
