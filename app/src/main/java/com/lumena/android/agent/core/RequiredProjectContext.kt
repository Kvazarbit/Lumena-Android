package com.lumena.android.agent.core

/** Backend compaction must retain the entire app-authored mandatory block. */
object RequiredProjectContext {
    const val BEGIN = "[LUMENA_REQUIRED_CONTEXT_V1_BEGIN]"
    const val END = "[LUMENA_REQUIRED_CONTEXT_V1_END]"
    fun wrap(text: String): String = "$BEGIN\n$text\n$END"
    fun block(text: String): String? {
        val start = text.indexOf(BEGIN)
        val end = text.indexOf(END)
        if (start < 0 && end < 0) return null
        require(start >= 0 && end > start && text.indexOf(BEGIN, start + BEGIN.length) < 0 &&
            text.indexOf(END, end + END.length) < 0) { "PROJECT_CONTEXT_INVALID: malformed protected context" }
        return text.substring(start, end + END.length)
    }
    fun minimum(text: String): Int = block(text)?.length ?: 0
    fun clip(text: String, limit: Int, fallback: (String, Int) -> String): String {
        val required = block(text) ?: return fallback(text, limit)
        require(required.length <= limit) {
            "PROJECT_CONTEXT_BUDGET: обов'язковий контекст не вміщується; збільш контекст моделі або звузь відкриті зобов'язання"
        }
        if (text.length <= limit) return text
        val start = text.indexOf(BEGIN)
        val before = text.substring(0, start)
        val after = text.substring(start + required.length)
        val extra = limit - required.length
        val head = minOf(before.length, extra * 2 / 3)
        val tail = minOf(after.length, extra - head)
        return before.take(head) + required + after.takeLast(tail)
    }
}
