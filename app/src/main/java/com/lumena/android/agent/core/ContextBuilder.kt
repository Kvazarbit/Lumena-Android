package com.lumena.android.agent.core

data class VerifiedProjectContext(
    val projectName: String,
    val cwd: String,
    val branch: String? = null,
    val importantFiles: List<String> = emptyList(),
    val verifiedFacts: List<String> = emptyList()
)

class ContextBuilder(
    private val maxMemoryItems: Int = 8,
    private val maxChars: Int = 14_000
) {
    fun build(
        task: TaskState,
        project: VerifiedProjectContext?,
        relevantMemory: List<String>,
        allowedTools: Set<String>? = null
    ): String {
        val text = buildString {
            appendLine("SYSTEM")
            appendLine("You are Lumena Local Agent. Choose only the single next safe action.")
            appendLine("Never claim a tool ran unless a TOOL_RESULT was provided.")
            appendLine("Never invent files, project state, command results, or capabilities.")
            appendLine()

            appendLine("AVAILABLE TOOLS")
            appendLine(ToolRegistry.renderForPrompt(allowedTools))
            appendLine()

            project?.let {
                appendLine("PROJECT STATE")
                appendLine("name=${it.projectName}")
                appendLine("cwd=${it.cwd}")
                it.branch?.let { branch -> appendLine("branch=$branch") }
                if (it.importantFiles.isNotEmpty()) {
                    appendLine("important_files=${it.importantFiles.take(16).joinToString()}")
                }
                if (it.verifiedFacts.isNotEmpty()) {
                    appendLine("verified_facts:")
                    it.verifiedFacts.take(16).forEach { fact -> appendLine("- ${sanitize(fact)}") }
                }
                appendLine()
            }

            appendLine("TASK STATE")
            appendLine("goal=${sanitize(task.goal)}")
            appendLine("status=${task.status}")
            appendLine("step=${task.step}/${task.maxSteps}")
            task.lastTool?.let { appendLine("last_tool=$it") }
            task.lastResult?.let { appendLine("last_result=${sanitize(it).take(2_000)}") }
            if (task.createdFiles.isNotEmpty()) appendLine("created=${task.createdFiles.take(16).joinToString()}")
            if (task.modifiedFiles.isNotEmpty()) appendLine("modified=${task.modifiedFiles.take(16).joinToString()}")
            if (task.errors.isNotEmpty()) {
                appendLine("recent_errors:")
                task.errors.takeLast(3).forEach { error -> appendLine("- ${sanitize(error).take(1_000)}") }
            }
            appendLine()

            val memory = relevantMemory
                .map(::sanitize)
                .filter { it.isNotBlank() }
                .distinct()
                .take(maxMemoryItems)
            if (memory.isNotEmpty()) {
                appendLine("RELEVANT VERIFIED MEMORY")
                memory.forEach { appendLine("- $it") }
            }

            appendLine()
            appendLine("OUTPUT RULE")
            appendLine("For a tool call return ONLY JSON: {\"tool\":\"...\",\"args\":{},\"reason\":\"...\"}")
            appendLine("If complete return ONLY JSON: {\"done\":true,\"summary\":\"...\"}")
            appendLine("Otherwise answer normally.")
        }

        return if (text.length <= maxChars) text else text.take(maxChars) + "\n[context truncated by app]"
    }

    private fun sanitize(value: String): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
}
