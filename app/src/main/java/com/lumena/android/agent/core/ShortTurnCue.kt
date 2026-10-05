package com.lumena.android.agent.core

/**
 * Bare conversational cues that refer to existing work.
 *
 * Such a turn must bind to the active task or be answered honestly; it must
 * never become a standalone goal ("реалізуй" as a web query) and it can never
 * justify a completion claim without execution evidence.
 */
object ShortTurnCue {
    private const val MAX_CUE_CHARS = 32

    private val explain = Regex(
        "(?iu)^(?:[?!]+|" +
            "(?:а|і|и|ну|так)?\\s*(?:що|шо|что|чо|what|huh|co|i\\s+co|и\\s+что|і\\s+що)\\s*[?!]*|" +
            "(?:а|ну|so|and)\\s*[?!]+|" +
            "що\\s+(?:там|далі|з\\s+задачею)\\s*[?!]*|" +
            "what\\s+now\\s*[?!]*" +
            ")$"
    )

    private val execute = Regex(
        "(?iu)^(?:реалізуй|реалізовуй|зроби|роби|виконай|виконуй|давай|поїхали|вперед|" +
            "реализуй|сделай|делай|выполни|поехали|" +
            "implement|do\\s+it|go|go\\s+ahead|proceed|execute|make\\s+it|" +
            "zaimplementuj|zrób|rób|wykonaj|dawaj)" +
            "(?:\\s+(?:це|його|її|это|его|it|this|to))?\\s*[.!]*$"
    )

    private fun normalized(text: String): String =
        text.trim().replace(Regex("\\s+"), " ")

    fun isExplain(text: String): Boolean {
        val value = normalized(text)
        return value.isNotEmpty() &&
            value.length <= MAX_CUE_CHARS &&
            explain.matches(value)
    }

    fun isExecutionDirective(text: String): Boolean {
        val value = normalized(text)
        return value.isNotEmpty() &&
            value.length <= MAX_CUE_CHARS &&
            execute.matches(value)
    }

    /**
     * Neutral wording on purpose: it must route to GENERAL (no web preflight,
     * no code-tool obligation) so that "?" is answered from recorded state.
     */
    const val EXPLAIN_GOAL =
        "Поясни користувачу поточний стан попередньої задачі за записаними " +
            "результатами. Нових дій не виконуй."

    private val emptyCompletionSummaries = setOf(
        "",
        "task complete",
        "task completed",
        "done",
        "complete",
        "completed",
        "готово",
        "виконано",
        "зроблено",
        "задачу виконано",
        "завдання виконано"
    )

    /** Summary that only asserts completion without saying what happened. */
    fun isEmptyCompletionClaim(summary: String): Boolean =
        summary.trim().lowercase().trimEnd('.', '!', '…').trim() in
            emptyCompletionSummaries
}
