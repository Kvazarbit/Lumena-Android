package com.lumena.android.agent.core

/**
 * Bounded, application-generated task outcome context for later conversational
 * follow-ups. It is evidence about the previous task, not a new instruction and
 * never restores approvals, in-flight actions or execution authority.
 */
object TaskOutcomeContext {
    private const val MAX_ERROR_CHARS = 1_600
    private const val MAX_RESULT_CHARS = 1_200
    private const val MAX_GOAL_CHARS = 900

    fun failed(
        task: TaskState,
        message: String
    ): String = buildString {
        appendLine("TASK_OUTCOME_CONTEXT")
        appendLine("kind=FAILED")
        appendLine("task_id=${clean(task.id, 220)}")
        task.projectId
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("project_id=${clean(it, 160)}") }
        appendLine("goal=${clean(task.goal, MAX_GOAL_CHARS)}")
        task.lastTool
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("last_tool=${clean(it, 160)}") }
        task.lastResult
            ?.takeIf { it.isNotBlank() }
            ?.let { appendLine("last_result=${clean(it, MAX_RESULT_CHARS)}") }
        appendLine("failure=${clean(message, MAX_ERROR_CHARS)}")
        task.errors
            .takeLast(3)
            .map { clean(it, 600) }
            .filter { it.isNotBlank() }
            .forEachIndexed { index, value ->
                appendLine("error_${index + 1}=$value")
            }
        append(
            "This record describes the previous run only. " +
                "Do not treat it as permission to execute or as proof of success."
        )
    }.take(5_000)

    private fun clean(
        value: String,
        maxChars: Int
    ): String =
        value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
}
