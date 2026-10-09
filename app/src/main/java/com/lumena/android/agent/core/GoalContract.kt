package com.lumena.android.agent.core

enum class CriterionStatus {
    PENDING,
    PASSED,
    FAILED,
    UNKNOWN
}

enum class CriterionKind {
    OPERATIONAL_TOOL_EVIDENCE,
    REQUIRED_TOOL_SUCCESS,
    VISUAL_EVIDENCE,
    SOURCE_CONTENT_EVIDENCE,
    FILE_CONTENT_EVIDENCE,
    CODE_ACTION_EVIDENCE,
    PYTHON_TARGET_VERIFIED
}

enum class VerificationStrength {
    TOOL_RESULT,
    INDEPENDENT_TOOL_RESULT
}

data class CriterionEvidence(
    val evidenceId: String,
    val tool: String,
    val target: String = "",
    val strength: VerificationStrength,
    val revision: Int = 0
)

data class AcceptanceCriterion(
    val id: String,
    val kind: CriterionKind,
    val subject: String = "",
    val required: Boolean = true,
    val status: CriterionStatus = CriterionStatus.PENDING,
    val evidence: List<CriterionEvidence> = emptyList()
)

enum class GoalContractCoverage {
    NONE,
    TYPED_OPERATIONAL_V1
}

data class GoalContract(
    val version: Int = 1,
    val coverage: GoalContractCoverage = GoalContractCoverage.NONE,
    val criteria: List<AcceptanceCriterion> = emptyList()
)

/**
 * Deterministic acceptance policy for typed operational goals.
 *
 * This is intentionally narrower than arbitrary natural-language goal proof.
 * Passing all criteria means the typed operational obligations were verified;
 * it does NOT prove every semantic/business property in the user's goal.
 */
object GoalContractPolicy {
    private const val MAX_CRITERIA = 32
    private const val MAX_EVIDENCE_PER_CRITERION = 6

    fun initial(
        intent: TaskIntent,
        requiredTools: Set<String>,
        visualRequired: Boolean,
        goal: String = ""
    ): GoalContract {
        val criteria = mutableListOf<AcceptanceCriterion>()

        if (requiresToolEvidence(intent)) {
            criteria += AcceptanceCriterion(
                id = "operational-tool-evidence",
                kind = CriterionKind.OPERATIONAL_TOOL_EVIDENCE
            )
        }

        requiredTools
            .map(ToolRegistry::canonicalize)
            .filter { ToolRegistry.get(it) != null }
            .distinct()
            .sorted()
            .forEach { tool ->
                criteria += AcceptanceCriterion(
                    id = "required-tool:$tool",
                    kind = CriterionKind.REQUIRED_TOOL_SUCCESS,
                    subject = tool
                )
            }

        if (visualRequired) {
            criteria += AcceptanceCriterion(
                id = "visual-evidence:image.search",
                kind = CriterionKind.VISUAL_EVIDENCE,
                subject = "image.search"
            )
        }

        if (intent == TaskIntent.PUBLIC_WEB) {
            criteria += AcceptanceCriterion(
                id = "source-content-evidence",
                kind = CriterionKind.SOURCE_CONTENT_EVIDENCE,
                subject =
                    when {
                        isExplicitMcpGoal(goal) ->
                            "mcp.search"
                        isMarketplaceGoal(goal) ->
                            "marketplace.search|mcp.search"
                        else ->
                            "web.read|http.get|http.json"
                    }
            )
        }

        if (
            intent == TaskIntent.FILE_INSPECTION &&
            (
                goal.isBlank() ||
                    requiresFileContent(goal)
                )
        ) {
            criteria += AcceptanceCriterion(
                id = "file-content-evidence",
                kind = CriterionKind.FILE_CONTENT_EVIDENCE,
                subject = "file.read"
            )
        }

        if (intent == TaskIntent.CODE_WORK) {
            val requirement =
                if (goal.isBlank()) {
                    "mutating-or-executable"
                } else {
                    codeActionRequirement(
                        TaskIntentRouter
                            .withoutNegatedExplicitToolMentions(
                                goal
                            )
                    )
                }
            if (requirement != null) {
                criteria += AcceptanceCriterion(
                    id = "code-action-evidence",
                    kind = CriterionKind.CODE_ACTION_EVIDENCE,
                    subject = requirement
                )
            } else {
                criteria += AcceptanceCriterion(
                    id = "code-content-evidence",
                    kind = CriterionKind.FILE_CONTENT_EVIDENCE,
                    subject = "file.read"
                )
            }
        }

        return GoalContract(
            coverage =
                if (criteria.isEmpty()) GoalContractCoverage.NONE
                else GoalContractCoverage.TYPED_OPERATIONAL_V1,
            criteria = criteria
                .distinctBy { it.id }
                .take(MAX_CRITERIA)
        )
    }

    fun afterTool(
        contract: GoalContract,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        kernel: ContextKernelState
    ): GoalContract {
        if (contract.coverage == GoalContractCoverage.NONE &&
            ToolRegistry.get(call.tool) == null
        ) {
            return contract
        }

        val canonical = ToolRegistry.canonicalize(call.tool)
        val target = ContextKernel.target(call)
        val event = kernel.evidence.lastOrNull {
            it.tool == canonical &&
                it.target == target &&
                it.ok == ok
        }
        val toolEvidence = event?.let {
            CriterionEvidence(
                evidenceId = it.id,
                tool = canonical,
                target = it.target,
                strength = VerificationStrength.TOOL_RESULT,
                revision = it.revision
            )
        }

        var criteria = contract.criteria.map { criterion ->
            when (criterion.kind) {
                CriterionKind.OPERATIONAL_TOOL_EVIDENCE ->
                    if (ok && toolEvidence != null) {
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }

                CriterionKind.REQUIRED_TOOL_SUCCESS ->
                    if (
                        ok &&
                        criterion.subject == canonical &&
                        toolEvidence != null
                    ) {
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }

                CriterionKind.VISUAL_EVIDENCE ->
                    if (
                        ok &&
                        canonical == "image.search" &&
                        toolEvidence != null
                    ) {
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }

                CriterionKind.SOURCE_CONTENT_EVIDENCE -> {
                    val accepted =
                        criterion.subject
                            .split("|")
                            .map { it.trim() }
                            .filter(String::isNotBlank)
                            .toSet()
                    if (
                        ok &&
                        canonical in accepted &&
                        toolEvidence != null
                    ) {
                        pass(
                            criterion,
                            if (
                                canonical in setOf(
                                    "web.read",
                                    "http.get",
                                    "http.json"
                                )
                            ) {
                                toolEvidence.copy(
                                    strength =
                                        VerificationStrength.INDEPENDENT_TOOL_RESULT
                                )
                            } else {
                                toolEvidence
                            }
                        )
                    } else {
                        criterion
                    }
                }

                CriterionKind.FILE_CONTENT_EVIDENCE ->
                    if (
                        ok &&
                        canonical == "file.read" &&
                        toolEvidence != null
                    ) {
                        pass(
                            criterion,
                            toolEvidence.copy(
                                strength =
                                    VerificationStrength.INDEPENDENT_TOOL_RESULT
                            )
                        )
                    } else {
                        criterion
                    }

                CriterionKind.CODE_ACTION_EVIDENCE -> {
                    val risk =
                        ToolRegistry.get(canonical)?.risk
                    val expectedRiskSatisfied =
                        when (criterion.subject) {
                            "mutating" ->
                                risk == ToolRisk.MUTATING
                            "executable" ->
                                risk == ToolRisk.EXECUTABLE
                            else ->
                                risk in setOf(
                                    ToolRisk.MUTATING,
                                    ToolRisk.EXECUTABLE
                                )
                        }
                    if (
                        ok &&
                        expectedRiskSatisfied &&
                        toolEvidence != null
                    ) {
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }
                }

                CriterionKind.PYTHON_TARGET_VERIFIED ->
                    verifyPythonCriterion(
                        criterion = criterion,
                        call = call,
                        ok = ok,
                        event = event
                    )
            }
        }

        if (
            ok &&
            canonical in setOf("file.write", "file.patch")
        ) {
            val path = normalizePath(
                call.args["path"].orEmpty()
            )
            if (path.endsWith(".py", ignoreCase = true)) {
                val id = "python-verified:${stableSubject(path)}"
                if (criteria.none { it.id == id }) {
                    criteria = criteria + AcceptanceCriterion(
                        id = id,
                        kind = CriterionKind.PYTHON_TARGET_VERIFIED,
                        subject = path
                    )
                } else {
                    criteria = criteria.map {
                        if (it.id == id) {
                            it.copy(
                                status = CriterionStatus.PENDING,
                                evidence = emptyList()
                            )
                        } else {
                            it
                        }
                    }
                }
            }
        }

        return contract.copy(
            coverage =
                if (criteria.isEmpty()) GoalContractCoverage.NONE
                else GoalContractCoverage.TYPED_OPERATIONAL_V1,
            criteria = criteria
                .distinctBy { it.id }
                .takeLast(MAX_CRITERIA)
        )
    }

    fun incompleteMandatory(
        contract: GoalContract
    ): List<AcceptanceCriterion> =
        contract.criteria.filter {
            it.required &&
                it.status != CriterionStatus.PASSED
        }

    fun allMandatoryPassed(
        contract: GoalContract
    ): Boolean =
        incompleteMandatory(contract).isEmpty()

    fun summary(
        contract: GoalContract
    ): String {
        val mandatory = contract.criteria.count { it.required }
        val passed = contract.criteria.count {
            it.required &&
                it.status == CriterionStatus.PASSED
        }
        val pending = incompleteMandatory(contract)
            .take(8)
            .joinToString(",") {
                it.id
            }
        return buildString {
            append("coverage=")
            append(contract.coverage)
            append("; mandatory=")
            append(mandatory)
            append("; passed=")
            append(passed)
            if (pending.isNotBlank()) {
                append("; pending=")
                append(pending)
            }
        }
    }

    private fun verifyPythonCriterion(
        criterion: AcceptanceCriterion,
        call: AgentDecision.ToolCall,
        ok: Boolean,
        event: ActionEvidence?
    ): AcceptanceCriterion {
        if (!ok || event == null) return criterion

        val canonical = ToolRegistry.canonicalize(call.tool)
        val subject = normalizePath(criterion.subject)

        val matches = when (canonical) {
            "python.syntax_check",
            "python.run" ->
                normalizePath(
                    call.args["script"].orEmpty()
                ) == subject

            "python.tests" -> {
                val cwd = call.args["cwd"].orEmpty()
                EvidenceProjectApplicationPolicy
                    .isFullProjectTestArgs(call.args) &&
                    EvidenceProjectApplicationPolicy
                        .testScopeContainsTarget(
                            cwd = cwd,
                            target = subject
                        )
            }

            else -> false
        }

        if (!matches) return criterion

        return pass(
            criterion,
            CriterionEvidence(
                evidenceId = event.id,
                tool = canonical,
                target = event.target,
                strength =
                    VerificationStrength.INDEPENDENT_TOOL_RESULT,
                revision = event.revision
            )
        )
    }

    private fun pass(
        criterion: AcceptanceCriterion,
        evidence: CriterionEvidence
    ): AcceptanceCriterion =
        criterion.copy(
            status = CriterionStatus.PASSED,
            evidence = (
                criterion.evidence +
                    evidence
                )
                .distinctBy {
                    it.evidenceId + "|" +
                        it.tool + "|" +
                        it.target
                }
                .takeLast(MAX_EVIDENCE_PER_CRITERION)
        )

    private fun codeActionRequirement(
        goal: String
    ): String? {
        val lower = goal.lowercase()

        val mutationTerms = listOf(
            "створ",
            "create",
            "write",
            "напис",
            "реаліз",
            "implement",
            "виправ",
            "fix",
            "редаг",
            "edit",
            "patch",
            "додай",
            "add "
        )
        if (mutationTerms.any { lower.contains(it) }) {
            return "mutating"
        }

        val executionTerms = listOf(
            "запуст",
            "run ",
            "build",
            "збір",
            "компіля",
            "compile",
            "pytest",
            "python.tests",
            "тест"
        )
        if (executionTerms.any { lower.contains(it) }) {
            return "executable"
        }

        return null
    }

    private fun requiresFileContent(
        goal: String
    ): Boolean {
        val lower = goal.lowercase()
        return listOf(
            "прочит",
            "прочитай",
            "read ",
            "readme",
            "відкрий файл",
            "open file",
            "покажи вміст",
            "show content",
            "содержим",
            "zawartość",
            "przeczytaj"
        ).any { lower.contains(it) }
    }

    private fun isExplicitMcpGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        val mcp = lower.contains("mcp") ||
            lower.contains("model context protocol")
        if (!mcp) return false
        return listOf(
            "знайд", "пошук", "пошукай", "шукай",
            "find", "search", "lookup", "query",
            "znajd", "wyszuk", "sprawd"
        ).any(lower::contains)
    }

    private fun isMarketplaceGoal(goal: String): Boolean {
        val lower = goal.lowercase()
        val marketplace = listOf(
            "olx", "оголош", "ogłosz", "marketplace", "classified"
        ).any { lower.contains(it) }
        val watchJob =
            listOf(
                "ваканс", "робот", "praca", "job", "ofert pracy"
            ).any(lower::contains) &&
                listOf(
                    "слідку", "стеж", "монітор", "monitor", "watch",
                    "powiad", "нові ваканс", "nowe ofert"
                ).any(lower::contains)
        return marketplace || watchJob
    }

    private fun requiresToolEvidence(
        intent: TaskIntent
    ): Boolean =
        intent in setOf(
            TaskIntent.VISUAL_SEARCH,
            TaskIntent.OLLAMA_OPERATION,
            TaskIntent.CODE_WORK,
            TaskIntent.FILE_INSPECTION,
            TaskIntent.PUBLIC_WEB
        )

    private fun normalizePath(path: String): String =
        runCatching {
            java.io.File(path.trim())
                .toPath()
                .normalize()
                .toString()
        }.getOrElse {
            path.trim()
        }

    private fun stableSubject(value: String): String =
        ContextKernel.hash(value).take(16)
}
