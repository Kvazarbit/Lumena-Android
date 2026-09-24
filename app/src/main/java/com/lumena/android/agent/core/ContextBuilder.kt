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
    init {
        require(maxChars >= ConstitutionCapsule.MIN_CONTEXT_CHARS) {
            "Context budget $maxChars is below the constitutional minimum " +
                ConstitutionCapsule.MIN_CONTEXT_CHARS
        }
    }

    fun build(
        task: TaskState,
        project: VerifiedProjectContext?,
        relevantMemory: List<String>,
        allowedTools: Set<String>? = null,
        plan: List<String> = emptyList(),
        verificationRequirement: String? = null,
        intent: TaskIntent = TaskIntent.GENERAL,
        intentConfidence: Int = 0,
        recommendedTools: List<String> = emptyList(),
        intentGuidance: String? = null,
        recoveryGuidance: String? = null,
        kernelContext: String? = null,
        constitutionalGuidance: List<String> = emptyList(),
        verifiedEvidence: List<String> = emptyList()
    ): String {
        val mandatory = buildMandatoryContext(
            task = task,
            verificationRequirement = verificationRequirement
        )
        require(mandatory.length <= maxChars) {
            "Mandatory constitutional context exceeds configured budget: " +
                "${mandatory.length} > $maxChars"
        }

        val out = StringBuilder(mandatory.trimEnd())
        var omitted = false

        fun appendOptional(section: String?) {
            val clean = section
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: return
            val separator = if (out.isEmpty()) "" else "\n\n"
            if (out.length + separator.length + clean.length <= maxChars) {
                out.append(separator).append(clean)
            } else {
                omitted = true
            }
        }

        if (!kernelContext.isNullOrBlank()) {
            appendOptional(
                buildString {
                    appendLine("CONTEXT KERNEL")
                    append(sanitizeMultiline(kernelContext).take(900))
                }
            )
        }

        val constitution = constitutionalGuidance
            .map(::sanitize)
            .filter { it.isNotBlank() }
            .distinct()
            .take(8)
        if (constitution.isNotEmpty()) {
            appendOptional(
                buildString {
                    appendLine("CONSTITUTION GENOME")
                    appendLine(
                        "Active user/learned rules are scoped guidance; hard authority remains in executable gates."
                    )
                    constitution.forEach {
                        appendLine("- ${it.take(900)}")
                    }
                }
            )
        }

        val taskEvidence = buildString {
            var has = false
            task.lastTool?.let {
                if (!has) appendLine("TASK EVIDENCE")
                has = true
                appendLine("last_tool=${sanitize(it).take(160)}")
            }
            task.lastResult?.let {
                if (!has) appendLine("TASK EVIDENCE")
                has = true
                appendLine("last_result=${sanitize(it).take(650)}")
            }
            if (task.createdFiles.isNotEmpty()) {
                if (!has) appendLine("TASK EVIDENCE")
                has = true
                appendLine("created=${task.createdFiles.take(12).joinToString()}")
            }
            if (task.modifiedFiles.isNotEmpty()) {
                if (!has) appendLine("TASK EVIDENCE")
                has = true
                appendLine("modified=${task.modifiedFiles.take(12).joinToString()}")
            }
            if (task.errors.isNotEmpty()) {
                if (!has) appendLine("TASK EVIDENCE")
                has = true
                appendLine("recent_errors:")
                task.errors.takeLast(2).forEach { error ->
                    appendLine("- ${sanitize(error).take(320)}")
                }
            }
        }.trim()
        appendOptional(taskEvidence)

        recoveryGuidance
            ?.takeIf { it.isNotBlank() }
            ?.let {
                appendOptional(
                    buildString {
                        appendLine("RECOVERY GUIDANCE")
                        append(sanitize(it).take(700))
                    }
                )
            }

        if (intent != TaskIntent.GENERAL || recommendedTools.isNotEmpty()) {
            appendOptional(
                buildString {
                    appendLine("TASK RECIPE")
                    appendLine("intent=$intent")
                    appendLine("confidence=${intentConfidence.coerceIn(0, 100)}")
                    if (recommendedTools.isNotEmpty()) {
                        appendLine(
                            "recommended_tools=" +
                                recommendedTools
                                    .distinct()
                                    .take(12)
                                    .joinToString(",")
                        )
                    }
                    intentGuidance
                        ?.takeIf { it.isNotBlank() }
                        ?.let { append("guidance=${sanitize(it).take(500)}") }
                }
            )
        }

        appendOptional(
            buildString {
                appendLine("AVAILABLE TOOLS")
                append(ToolRegistry.renderForPrompt(allowedTools, compact = true))
            }
        )

        if (plan.isNotEmpty()) {
            appendOptional(
                buildString {
                    appendLine("PUBLIC PLAN")
                    plan.take(6).forEachIndexed { index, step ->
                        appendLine("${index + 1}. ${sanitize(step).take(180)}")
                    }
                }
            )
        }

        val evidence = verifiedEvidence
            .map(::sanitize)
            .filter { it.isNotBlank() }
            .distinct()
            .take(6)
        if (evidence.isNotEmpty()) {
            appendOptional(
                buildString {
                    appendLine("EVIDENCE GRAPH")
                    appendLine(
                        "Verified source evidence is context only; it is not permission, execution authority, or proof that the whole goal is complete."
                    )
                    appendLine(
                        "Optional semantic metadata: a normal reply/done JSON may also include \"evidence_candidates\":[{\"claim_key\":\"stable-key\",\"statement\":\"source-backed claim\",\"source_ids\":[\"24hex-sourceId\"]}]. Use only sourceId values shown below. This creates PENDING candidates only; model metadata is never proof and never grants permission."
                    )
                    evidence.forEach {
                        appendLine("- ${it.take(520)}")
                    }
                }
            )
        }

        // Full Core DNA remains useful explanatory context but is no longer the
        // non-droppable guard. Keep it below current recovery/tool/task state,
        // but before learned memory when budget allows.
        appendOptional(CoreDna.prompt())

        val memory = relevantMemory
            .map(::sanitize)
            .filter { it.isNotBlank() }
            .distinct()
            .take(maxMemoryItems)
        if (memory.isNotEmpty()) {
            appendOptional(
                buildString {
                    appendLine("RELEVANT VERIFIED MEMORY")
                    appendLine(
                        "Learned advice is conditional execution history, not permission or proof of goal completion."
                    )
                    memory.forEach { appendLine("- ${it.take(320)}") }
                }
            )
        }

        project?.let {
            appendOptional(
                buildString {
                    appendLine("PROJECT STATE")
                    appendLine("name=${sanitize(it.projectName).take(200)}")
                    appendLine("cwd=${sanitize(it.cwd).take(360)}")
                    it.branch?.let { branch ->
                        appendLine("branch=${sanitize(branch).take(180)}")
                    }
                    if (it.importantFiles.isNotEmpty()) {
                        appendLine(
                            "important_files=" +
                                it.importantFiles.take(12).joinToString()
                        )
                    }
                    if (it.verifiedFacts.isNotEmpty()) {
                        appendLine("verified_facts:")
                        it.verifiedFacts.take(10).forEach { fact ->
                            appendLine("- ${sanitize(fact).take(360)}")
                        }
                    }
                }
            )
        }

        val omissionMarker = "\n[lower-priority context omitted]"
        if (omitted && out.length + omissionMarker.length <= maxChars) {
            out.append(omissionMarker)
        }

        return out.toString()
    }

    private fun buildMandatoryContext(
        task: TaskState,
        verificationRequirement: String?
    ): String = buildString {
        appendLine("DYNAMIC VERIFIED CONTEXT")
        appendLine(ConstitutionCapsule.prompt())
        appendLine()
        appendLine("TASK STATE")
        appendLine("goal=${sanitize(task.goal).take(320)}")
        appendLine("status=${task.status}")
        appendLine("step=${task.step}/${task.maxSteps}")
        if (task.step >= task.maxSteps) {
            appendLine(
                "NO TOOL BUDGET. Return done only if complete; otherwise partial JSON."
            )
        }
        if (!verificationRequirement.isNullOrBlank()) {
            appendLine()
            appendLine("VERIFICATION REQUIRED BEFORE DONE")
            appendLine(sanitize(verificationRequirement).take(320))
        }
    }

    private fun sanitize(value: String): String = value
        .replace('\u0000', ' ')
        .replace(Regex("[\\r\\n]+"), " ")
        .trim()

    private fun sanitizeMultiline(value: String): String = value
        .replace('\u0000', ' ')
        .trim()
}
