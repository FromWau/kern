package com.fromwau.kern.logger.sentry

// Sentry rejects an event over 1 MB; its up to 100 breadcrumbs at 2 KiB each leave most of that to the event itself.
private const val ENTRY_BYTES = 8 * 1024
private const val BREADCRUMB_BYTES = 2 * 1024

// The `{}` around an object's entries, less the comma its last entry goes without.
private const val OBJECT_OVERHEAD = 1

private const val TRUNCATED = "…(truncated)"
private const val LEFT_OUT = "…"
private val truncatedCost = TRUNCATED.jsonCost()

private const val CUT_FLOOR = 256
private const val SINK_TEXT_FLOOR = 1024

/**
 * Where an entry goes, by the most its object there may take and the most JSON a field adds to it beyond its key and
 * value, as sentry-java writes it.
 *
 * @param budget the most the object takes: an event's extras, a breadcrumb's data or a log's attributes.
 * @param textOverhead around a text value, its quotes included.
 * @param numberOverhead around a number or a boolean.
 */
internal enum class Destination(
    val budget: Int,
    private val textOverhead: Int,
    private val numberOverhead: Int,
) {
    // An extra, `"":"",`: every extra is text, so a number goes in quotes too.
    Event(ENTRY_BYTES, textOverhead = 6, numberOverhead = 6),

    // A data entry, `"":"",`, or `"":,` for a number or a boolean.
    Breadcrumb(BREADCRUMB_BYTES, textOverhead = 6, numberOverhead = 4),

    // An attribute, `"":{"type":"string","value":""},`; the other type names make none longer.
    Log(ENTRY_BYTES, textOverhead = 32, numberOverhead = 32),
    ;

    fun keyCost(
        key: String,
        value: Any,
    ): Int = (if (value is String) textOverhead else numberOverhead) + key.jsonCost()
}

/** The fields and the sink's own keys as one destination takes them. */
internal class FittedEntry(
    val fields: Map<String, Any>,
    val sinkValues: Map<String, String>,
)

/** One field as [fitToEntryBudget] weighs it on one destination. */
private class WeighedField(
    val key: String,
    val value: Any,
    destination: Destination,
) {
    val isText = value is String
    val keyCost = destination.keyCost(key, value)
    val valueCost = value.jsonCost()
    val fullCost: Long = keyCost.toLong() + valueCost

    /** What it takes with its text cut as far as it may go. */
    val leastCost = keyCost + if (isText) minOf(valueCost, CUT_FLOOR) else valueCost

    /** What it takes beyond the text a cut may shorten. */
    val uncutCost = keyCost + if (isText) 0 else valueCost
}

/**
 * [fields] and [sinkTexts] within [destination]'s budget beside [sinkKeys], as its object takes them, keys and JSON
 * included. Over that, the longest texts are cut first, each to the same size ending in [TRUNCATED] but never below
 * [CUT_FLOOR], and a sink text never below [SINK_TEXT_FLOOR]. If that is not enough, the costliest fields are left out
 * first, the others keeping their order, and one field [LEFT_OUT] says how many. A sink text is never left out, and
 * [sinkKeys] are neither cut nor left out.
 */
internal fun fitToEntryBudget(
    fields: List<Pair<String, Any>>,
    sinkTexts: List<Pair<String, String>>,
    sinkKeys: List<Pair<String, String>>,
    destination: Destination,
): FittedEntry {
    val budget = destination.budget - OBJECT_OVERHEAD -
        sinkKeys.sumOf { (key, value) -> destination.keyCost(key, value) + value.jsonCost() } -
        sinkTexts.sumOf { (key, text) -> destination.keyCost(key, text) }
    val weighed = fields.map { (key, value) -> WeighedField(key, value, destination) }
    val sinkTextCosts = sinkTexts.map { (_, text) -> text.jsonCost() }
    val wholeCost = weighed.sumOf { it.fullCost } + sinkTextCosts.sumOf { it.toLong() }
    if (wholeCost <= budget) return FittedEntry(fields.toMap(), (sinkKeys + sinkTexts).toMap())

    val leastSinkTextCost = sinkTextCosts.sumOf { minOf(it, SINK_TEXT_FLOOR) }
    fun leftOutCost(count: Int) =
        if (count == 0) 0 else destination.keyCost(LEFT_OUT, "") + leftOutText(count).jsonCost()

    // Greedy: the costliest fields at full cost go first, the later of equals first, until the rest fit cut as far as
    // they may go. That leaves out few fields, though not always the fewest.
    val leftOut = mutableSetOf<WeighedField>()
    var leastKeptCost = weighed.sumOf { it.leastCost.toLong() }
    for (field in weighed.asReversed().sortedByDescending { it.fullCost }) {
        if (leastKeptCost + leastSinkTextCost + leftOutCost(leftOut.size) <= budget) break
        leftOut += field
        leastKeptCost -= field.leastCost
    }

    val kept = weighed.filterNot { it in leftOut }
    val texts = kept.filter { it.isText }.map { CuttableText(it.value as String, it.valueCost, CUT_FLOOR) } +
        sinkTexts.mapIndexed { i, (_, text) -> CuttableText(text, sinkTextCosts[i], SINK_TEXT_FLOOR) }
    val textBudget = budget - kept.sumOf { it.uncutCost } - leftOutCost(leftOut.size)
    val fitted = cutLargestFirst(texts, textBudget).iterator()

    val fittedFields = buildMap {
        kept.forEach { field -> put(field.key, if (field.isText) fitted.next() else field.value) }
        if (leftOut.isNotEmpty()) put(LEFT_OUT, leftOutText(leftOut.size))
    }
    return FittedEntry(fittedFields, sinkKeys.toMap() + sinkTexts.associate { (key, _) -> key to fitted.next() })
}

private fun leftOutText(count: Int) = if (count == 1) "1 field left out" else "$count fields left out"

/** A text [cutLargestFirst] may cut, to no less than [floor]. */
private class CuttableText(
    val text: String,
    val cost: Int,
    private val floor: Int,
) {
    fun costWithin(cap: Int): Int {
        val limit = maxOf(cap, floor)
        return if (cost <= limit) cost else text.cutCost(limit)
    }

    fun within(cap: Int): String {
        val limit = maxOf(cap, floor)
        return if (cost <= limit) text else text.cutTo(limit)
    }
}

/**
 * [texts], each in its place, within [budget] together. Over that, every text costing more than one cap, or than its
 * floor when that is larger, is cut to it, [TRUNCATED] included, with the cap as large as fits, so the shorter texts
 * stay whole. The texts at their floors must fit [budget].
 */
private fun cutLargestFirst(
    texts: List<CuttableText>,
    budget: Int,
): List<String> {
    // Without texts there is nothing to cut, even where the sink's keys alone take more than the budget.
    if (texts.isEmpty() || texts.sumOf { it.cost.toLong() } <= budget) return texts.map { it.text }

    fun total(cap: Int): Long = texts.sumOf { it.costWithin(cap).toLong() }

    var low = CUT_FLOOR
    var high = texts.maxOf { it.cost } - 1
    while (low < high) {
        val mid = low + (high - low + 1) / 2
        if (total(mid) <= budget) low = mid else high = mid - 1
    }
    return texts.map { it.within(low) }
}

/** This text's longest start that fits [cap] with [TRUNCATED] after it, followed by [TRUNCATED]. */
private fun String.cutTo(cap: Int): String = substring(0, startFitting(cap - truncatedCost).first) + TRUNCATED

private fun String.cutCost(cap: Int): Int = startFitting(cap - truncatedCost).second + truncatedCost

/** The end index and [jsonCost] of this text's longest start within [maxCost], never splitting a surrogate pair. */
private fun String.startFitting(maxCost: Int): Pair<Int, Int> {
    var cost = 0
    var index = 0
    while (index < length) {
        val isPair = this[index].isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()
        val size = if (isPair) 4 else this[index].jsonCost()
        if (cost + size > maxCost) break
        cost += size
        index += if (isPair) 2 else 1
    }
    return index to cost
}

/** A value's size as Sentry receives it: a text escaped inside a JSON string, a number or boolean as its text. */
private fun Any.jsonCost(): Int = if (this is String) jsonCost() else toString().length

private fun String.jsonCost(): Int = startFitting(Int.MAX_VALUE).second

// Escaped as sentry-java's JSON writer escapes; a lone surrogate counts 3, the most an encoder writes in its place.
private fun Char.jsonCost(): Int = when (code) {
    '"'.code, '\\'.code, 0x08, 0x09, 0x0A, 0x0C, 0x0D -> 2
    in 0..<0x20, 0x2028, 0x2029 -> 6
    in 0..<0x80 -> 1
    in 0..<0x800 -> 2
    else -> 3
}
