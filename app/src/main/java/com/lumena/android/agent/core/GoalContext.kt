package com.lumena.android.agent.core

/**
 * Keeps both the root of a long user goal and its newest tail directives.
 *
 * Work-thread goals are intentionally composed as:
 * root goal -> recent directives.
 * Taking only the first N characters can therefore erase the instruction that
 * the user just added after a file inspection. This helper omits the middle
 * instead, preserving both ends without restoring any execution authority.
 */
object GoalContext {
    fun clip(
        value: String,
        limit: Int
    ): String {
        if (limit <= 0) return ""

        val clean = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

        if (clean.length <= limit) return clean

        val marker =
            " …[goal middle omitted]… "
        if (limit <= marker.length + 16) {
            return clean.take(limit)
        }

        val available =
            limit - marker.length
        val head =
            (available * 3) / 5
        val tail =
            available - head

        return clean.take(head) +
            marker +
            clean.takeLast(tail)
    }
}
