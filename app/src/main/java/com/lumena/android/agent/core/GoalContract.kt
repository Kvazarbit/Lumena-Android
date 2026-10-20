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
    VISUAL_METADATA_ALIGNMENT,
    SOURCE_CONTENT_EVIDENCE,
    REQUIRED_SOURCE_HOST,
    KOTLIN_COMPILE_VERIFIED,
    FILE_CONTENT_EVIDENCE,
    CODE_ACTION_EVIDENCE,
    PYTHON_TARGET_VERIFIED,
    PROJECT_VERIFICATION
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
    val evidence: List<CriterionEvidence> = emptyList(),
    val target: String? = null
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
            if (goal.isNotBlank()) {
                criteria += AcceptanceCriterion(
                    id = "visual-metadata-alignment",
                    kind = CriterionKind.VISUAL_METADATA_ALIGNMENT,
                    subject = goal.take(500)
                )
            }
        }

        if (intent == TaskIntent.PUBLIC_WEB) {
            criteria += AcceptanceCriterion(
                id = "source-content-evidence",
                kind = CriterionKind.SOURCE_CONTENT_EVIDENCE,
                subject =
                    ModuleRegistry.enabled()
                        .firstNotNullOfOrNull { it.sourceEvidenceTools(goal) }
                        ?.filter { ToolRegistry.get(it) != null }
                        ?.takeIf { it.isNotEmpty() }
                        ?.joinToString("|")
                        ?: "web.read|http.get|http.json"
            )
            SemanticGoalEvidence.requiredOfficialHost(goal)?.let { host ->
                criteria += AcceptanceCriterion(
                    id = "required-source-host:$host",
                    kind = CriterionKind.REQUIRED_SOURCE_HOST,
                    subject = host
                )
            }
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
            if (SemanticGoalEvidence.explicitKotlinCompilationGoal(goal)) {
                // The current Android ToolRegistry has no Kotlin/Gradle compiler.
                // Fail closed: file.write alone can never certify Kotlin syntax.
                criteria += AcceptanceCriterion(
                    id = "kotlin-compile-verification",
                    kind = CriterionKind.KOTLIN_COMPILE_VERIFIED,
                    subject = "kotlin"
                )
            }
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

        val requestedPaths = ProjectGoalTargets.explicit(goal)
        if (intent == TaskIntent.FILE_INSPECTION && requiresFileContent(goal)) {
            requestedPaths.forEach { path -> criteria += AcceptanceCriterion(
                "requested-file:" + stableSubject(path), CriterionKind.FILE_CONTENT_EVIDENCE,
                subject = "file.read", target = path)
            }
        }
        if (intent == TaskIntent.CODE_WORK && requestedPaths.isNotEmpty()) {
            val requirement = codeActionRequirement(goal)
            if (requirement == "mutating") ProjectGoalTargets.mutationTarget(goal)?.let { path -> criteria += AcceptanceCriterion(
                "requested-change:" + stableSubject(path), CriterionKind.CODE_ACTION_EVIDENCE,
                subject = "mutating", target = path) }
            if (TaskIntentRouter.isExplicitFileExecution(goal) && requestedPaths.first().endsWith(".py")) {
                criteria += AcceptanceCriterion("requested-python-run", CriterionKind.REQUIRED_TOOL_SUCCESS,
                    subject = "python.run", target = requestedPaths.first())
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
        kernel: ContextKernelState,
        stdout: String = ""
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
                        ProjectGoalTargets.matches(criterion.target, target) &&
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

                CriterionKind.VISUAL_METADATA_ALIGNMENT ->
                    if (
                        ok &&
                        canonical == "image.search" &&
                        toolEvidence != null &&
                        SemanticGoalEvidence.imageMetadataSupportsGoal(
                            goal = criterion.subject,
                            stdout = stdout
                        )
                    ) {
                        // The title/alt is untrusted metadata, NOT pixel verification.
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }

                CriterionKind.REQUIRED_SOURCE_HOST ->
                    if (
                        ok &&
                        toolEvidence != null &&
                        SemanticGoalEvidence.fetchedFromRequiredHost(
                            call = call,
                            stdout = stdout,
                            host = criterion.subject
                        )
                    ) {
                        pass(
                            criterion,
                            toolEvidence.copy(
                                strength = VerificationStrength.INDEPENDENT_TOOL_RESULT
                            )
                        )
                    } else {
                        criterion
                    }

                CriterionKind.KOTLIN_COMPILE_VERIFIED ->
                    // No supported, independently verified compiler tool yet.
                    // Never accept file.write, python.run, or a model claim as proof.
                    criterion

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
                        ProjectGoalTargets.matches(criterion.target, target) &&
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
                        ProjectGoalTargets.matches(criterion.target, target) &&
                        toolEvidence != null
                    ) {
                        pass(criterion, toolEvidence)
                    } else {
                        criterion
                    }
                }

                CriterionKind.PROJECT_VERIFICATION -> criterion // owned by ProjectVerificationPolicy

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

    private fun codeActionRequirement(goal: String): String? {
        // Permission names must never become mutation obligations.
        val text = goal.replace(
            Regex("(?iu)(?<![\\p{L}\\p{N}_])(?:ToolCapability\\.)?WRITE_WORKSPACE(?![\\p{L}\\p{N}_])"),
            " "
        )
        // Recognize whole directive words, not fragments in filenames or
        // descriptive/permission clauses.
        val mutate = Regex(
            "(?iu)\\b(?:створи|створіть|створити|створюй|зміни|змініть|змінити|змінюй|" +
                "виправ|виправи|виправте|виправити|редагуй|відредагуй|додай|додайте|онови|" +
                "реалізуй|реалізувати|создай|создать|измени|исправь|редактируй|добавь|" +
                "обнови|реализуй|create|write|edit|patch|fix|modify|implement|update|add|" +
                "zmień|zmien|napraw|dodaj|utwórz|utworz)\\b"
        )
        val negativeOrPermission = Regex(
            "(?iu)(?:\\b(?:не|ні|заборонено|дозволяю|дозволено|без|not|don't|never|" +
                "allow|allowed|permission|nie)\\b\\s*(?:[\\p{L}\\p{N}_-]+\\s+){0,2})$"
        )
        if (mutate.findAll(text).any { match ->
            val start = match.range.first
            val end = match.range.last + 1
            val identifier = (start > 0 && text[start - 1] == '.') ||
                (end < text.length && text[end] == '.')
            val prefix = text.substring(maxOf(0, start - 100), start)
                .substringAfterLast(';').substringAfterLast('\n').trimEnd()
            !identifier && !negativeOrPermission.containsMatchIn(prefix)
        }) return "mutating"

        val executionTerms = listOf(
            "запуст", "run ", "build", "збір", "компіля",
            "compile", "pytest", "python.tests", "тест"
        )
        if (TaskIntentRouter.isExplicitFileExecution(goal) ||
            executionTerms.any { goal.lowercase().contains(it) }) return "executable"
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
