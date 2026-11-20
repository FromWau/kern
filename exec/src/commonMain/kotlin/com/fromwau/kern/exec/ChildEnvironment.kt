package com.fromwau.kern.exec

/**
 * The child's variables by name: [inherited], this process's own as the bytes it holds, changed as [environment] says.
 * Inherited values stay bytes, since one that is not UTF-8 would not survive a round trip through a String.
 */
internal fun childEnvironment(
    environment: Environment,
    inherited: () -> Map<String, ByteArray>,
): Map<String, ByteArray> = when (environment) {
    Environment.Inherit -> inherited()
    is Environment.InheritWith -> inherited() + environment.set.encoded() - environment.unset
    is Environment.Exactly -> environment.variables.encoded()
}

/** [variables] as the `NAME=value` entries a process starts with. */
internal fun environmentEntries(variables: Map<String, ByteArray>): List<ByteArray> =
    variables.map { (name, value) -> "$name=".encodeToByteArray() + value }

/** The `NAME=value` [entry] split at its first `=`, or null when it has no name. */
internal fun splitEntry(entry: ByteArray): Pair<String, ByteArray>? {
    val separator = entry.indexOf('='.code.toByte())
    if (separator <= 0) return null
    return entry.copyOfRange(0, separator).decodeToString() to entry.copyOfRange(separator + 1, entry.size)
}

private fun Map<String, String>.encoded(): Map<String, ByteArray> = mapValues { it.value.encodeToByteArray() }
