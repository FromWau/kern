@file:OptIn(InternalKernApi::class)

package com.fromwau.kern.logger

/**
 * A value as text: containers as their contents at any depth, a placeholder where rendering throws, which is reported
 * through [reportRenderingThrew] as [subject], such as `field "count"`.
 */
@InternalKernApi
public fun fieldText(value: Any?, subject: String): String = try {
    value.contentText(enclosing = emptyList(), subject)
} catch (e: Throwable) {
    toStringThrew(e, subject)
}

/**
 * A value typed where a log tool keeps the type, a string, boolean or finite number, else its [fieldText] for
 * [subject].
 */
@InternalKernApi
public fun typedFieldValue(value: Any, subject: String): Any = when (value) {
    is String, is Boolean -> value
    // Before the integer types: on JS a NaN or an infinity passes one of their checks.
    // NaN and the infinities as text: JSON has no literal for them and Sentry's SDK cannot serialize them.
    is Double -> if (value.isFinite()) value else value.toString()
    is Float -> if (value.isFinite()) value else value.toString()
    is Int, is Long, is Short, is Byte -> value
    else -> fieldText(value, subject)
}

private fun Any?.contentText(enclosing: List<Any>, subject: String): String {
    // A container that holds itself reads as the standard library prints it, rather than overflowing the stack.
    if (this != null && enclosing.any { it === this }) {
        return when (this) {
            is Map<*, *> -> "(this Map)"
            is Collection<*> -> "(this Collection)"
            else -> "[...]"
        }
    }
    return when (this) {
        is Array<*> -> asList().elementsText(enclosing.plusElement(this), subject)
        is BooleanArray -> contentToString()
        is ByteArray -> contentToString()
        is CharArray -> contentToString()
        is ShortArray -> contentToString()
        is IntArray -> contentToString()
        is LongArray -> contentToString()
        is FloatArray -> contentToString()
        is DoubleArray -> contentToString()
        is Collection<*> -> elementsText(enclosing.plusElement(this), subject)
        is Map<*, *> -> {
            val inside = enclosing.plusElement(this)
            entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${key.contentText(inside, subject)}=${value.contentText(inside, subject)}"
            }
        }
        // Guarded on its own, so one element that throws leaves the rest of its container readable.
        else -> try {
            toString()
        } catch (e: Throwable) {
            toStringThrew(e, subject)
        }
    }
}

private fun Iterable<*>.elementsText(enclosing: List<Any>, subject: String): String =
    joinToString(prefix = "[", postfix = "]") { it.contentText(enclosing, subject) }

private fun toStringThrew(e: Throwable, subject: String): String {
    reportRenderingThrew("$subject: its toString() threw", e)
    return "<toString() threw ${e::class.simpleName}>"
}
