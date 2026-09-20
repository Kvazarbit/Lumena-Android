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
        allowedTools: Set<String>? = null,
        plan: List<String> = emptyList(),
        verificationRequirement: String? = null
    ): String {
        val sections = mutableListOf<String>()

        sections += buildString {
            appendLine("DYNAMIC VERIFIED CONTEXT")
            appendLine("Protocol reminder: tool/done/reply outputs are JSON only; TOOL_RESULT is the only execution proof.")
        }

        sections += buildString {
            appendLine("TASK STATE")
            appendLine("goal=${sanitize(task.goal).take(1_000)}")
            appendLine("status=${task.status}")
            appendLine("step=${task.step}/${task.maxSteps}")
            task.lastTool?.let { appendLine("last_tool=$it") }
            task.lastResult?.let { appendLine("last_result=${sanitize(it).take(800)}") }
            if (task.createdFiles.isNotEmpty()) appendLine("created=${task.createdFiles.take(16).joinToString()}")
            if (task.modifiedFiles.isNotEmpty()) appendLine("modified=${task.modifiedFiles.take(16).joinToString()}")
            if (task.errors.isNotEmpty()) {
                appendLine("recent_errors:")
                task.errors.takeLast(2).forEach { error -> appendLine("- ${sanitize(error).take(500)}") }
            }
        }

        if (!verificationRequirement.isNullOrBlank()) {
            sections += buildString {
                appendLine("VERIFICATION REQUIRED BEFORE DONE")
                appendLine(sanitize(verificationRequirement).take(1_000))
            }
        }

        sections += buildString {
            appendLine("AVAILABLE TOOLS")
            appendLine(ToolRegistry.renderForPrompt(allowedTools, compact = true))
        }

        if (plan.isNotEmpty()) {
            sections += buildString {
                appendLine("PUBLIC PLAN")
                plan.take(6).forEachIndexed { index, step ->
                    appendLine("${index + 1}. ${sanitize(step).take(180)}")
                }
            }
        }

        val memory = relevantMemory
            .map(::sanitize)
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxMemoryItems)
        if (memory.isNotEmpty()) {
            sections += buildString {
                appendLine("RELEVANT VERIFIED MEMORY")
                memory.forEach { appendLine("- ${it.take(360)}") }
            }
        }

        project?.let {
            sections += buildString {
                appendLine("PROJECT STATE")
                appendLine("name=${sanitize(it.projectName).take(240)}")
                appendLine("cwd=${sanitize(it.cwd).take(500)}")
                it.branch?.let { branch -> appendLine("branch=${sanitize(branch).take(240)}") }
                if (it.importantFiles.isNotEmpty()) {
                    appendLine("important_files=${it.importantFiles.take(16).joinToString()}")
                }
                if (it.verifiedFacts.isNotEmpty()) {
                    appendLine("verified_facts:")
                    it.verifiedFacts.take(16).forEach { fact -> appendLine("- ${sanitize(fact).take(600)}") }
                }
            }
        }

        val out = StringBuilder()
        var truncated = false
        for ((index, section) in sections.withIndex()) {
            val separator = if (out.isEmpty()) "" else "\n\n"
            val remaining = maxChars - out.length - separator.length
            if (remaining <= 0) {
                truncated = true
                break
            }

            if (section.length <= remaining) {
                out.append(separator).append(section.trimEnd())
                continue
            }

            if (index <= 2) {
                out.append(separator).append(section.take(remaining).trimEnd())
            }
            truncated = true
            break
        }

        // The marker is part of the same hard character budget, not extra output.
        val omissionMarker = "\n[lower-priority context omitted]"
        if (truncated && out.length + omissionMarker.length <= maxChars) {
            out.append(omissionMarker)
        }

        return out.toString()
    }

    private fun sanitize(value: String): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()
}
