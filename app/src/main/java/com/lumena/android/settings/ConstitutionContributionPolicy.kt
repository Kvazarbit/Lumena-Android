package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionEvidenceKind
import com.lumena.android.agent.core.ConstitutionEvidenceRef
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionProvenance
import com.lumena.android.agent.core.ConstitutionRule
import com.lumena.android.agent.core.ConstitutionRuleKind
import com.lumena.android.agent.core.ConstitutionScope
import com.lumena.android.agent.core.ConstitutionScopeKind
import com.lumena.android.agent.core.ConstitutionSourceKind
import com.lumena.android.agent.core.ConstitutionStance
import com.lumena.android.agent.core.TaskState
import com.lumena.android.agent.core.ToolRegistry
import java.security.MessageDigest

/**
 * Typed contribution adapters for the evolving Constitution Genome.
 *
 * Important boundary:
 * - verified project/tool outcomes may produce controlled-template ADVISORY rules;
 * - explicit user directives may produce USER_CONSTRAINT rules;
 * - model proposals are OBSERVATION only;
 * - none of these paths can create HARD_GUARD authority.
 *
 * Raw tool stdout/stderr and arbitrary model prose are never converted into an
 * active rule by the automatic recovery-example path.
 */
object ConstitutionContributionPolicy {
    private const val MAX_EXAMPLES_PER_PASS = 64

    fun ingestVerifiedRecoveryExamples(
        state: ConstitutionGenomeState,
        task: TaskState,
        examples: List<CoordinatorExecutionExample>
    ): ConstitutionGenomeState {
        var next = state
        examples
            .asSequence()
            .filter { it.kind == CoordinatorExampleKind.RECOVERY }
            .sortedWith(
                compareBy<CoordinatorExecutionExample> { it.updatedAt }
                    .thenBy { it.id }
            )
            .take(MAX_EXAMPLES_PER_PASS)
            .forEach { example ->
                val proposal = verifiedRecoveryRule(
                    task = task,
                    example = example
                ) ?: return@forEach
                next = ConstitutionGenomePolicy.contributeVerifiedAdvisory(
                    state = next,
                    proposal = proposal
                )
            }
        return next
    }

    fun verifiedRecoveryRule(
        task: TaskState,
        example: CoordinatorExecutionExample
    ): ConstitutionRule? {
        if (example.kind != CoordinatorExampleKind.RECOVERY) return null
        if (example.updatedAt <= 0) return null
        if (example.evidenceIds.isEmpty()) return null
        if (example.tools.size < 2 || example.tools.size > 6) return null

        val tools = example.tools.map { raw ->
            val canonical = ToolRegistry.canonicalize(raw)
            if (ToolRegistry.get(canonical) == null) return null
            canonical
        }
        val failedFamily = tools.first()
        if (tools.last() != failedFamily) return null

        val scope = scopeForTask(task)
        val middle = tools.drop(1).dropLast(1)
        val pattern = if (middle.isEmpty()) {
            "direct-retry"
        } else {
            "via-" + sha256(middle.joinToString(">")).take(16)
        }
        val claimKey = "recovery:$failedFamily:$pattern"
            .take(160)

        val statement = if (middle.isEmpty()) {
            "For $failedFamily failure in this scope, a bounded retry of the same operation has a verified recovery example; recheck current state before reuse."
        } else {
            "For $failedFamily failure in this scope, verified recovery used intermediate discovery/repair steps before the same operation succeeded; prefer a state-aware alternative before retrying."
        }
        val rationale = if (middle.isEmpty()) {
            "This pattern is derived from verified TOOL_RESULT-linked recovery episodes, not from model prose. It is advisory and remains subject to current state, budgets, ToolRegistry, ToolGate and confirmation."
        } else {
            "Verified recovery episodes show that repeating the failed operation only after intermediate discovery/repair can be more useful than an unchanged retry. The rule is scoped, advisory, and must be rechecked against current state."
        }

        val evidence = example.evidenceIds
            .asSequence()
            .filter { it.isNotBlank() }
            .distinct()
            .take(16)
            .map { evidenceId ->
                ConstitutionEvidenceRef(
                    id = evidenceId.take(180),
                    kind = ConstitutionEvidenceKind.TOOL_RESULT,
                    locallyVerified = true,
                    taskId = safeId(task.id),
                    projectId = task.projectId
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::safeId),
                    at = example.updatedAt
                )
            }
            .toList()

        if (evidence.isEmpty()) return null

        return ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.PROJECT_ARTIFACT,
                sourceId = "coordinator-example:" + safeId(example.id),
                projectId = task.projectId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::safeId),
                taskId = safeId(task.id),
                at = example.updatedAt
            ),
            claimKey = claimKey,
            stance = ConstitutionStance.AFFIRM,
            kind = ConstitutionRuleKind.RECOVERY,
            statement = statement,
            rationale = rationale,
            threatPrevented =
                "Blind repetition of a failed action while ignoring verified recovery structure.",
            scope = scope,
            evidenceRefs = evidence,
            enforcementPoints = listOf(
                "ConstitutionKernel",
                "ReflexKernel",
                "ToolRegistry",
                "ToolGate"
            ),
            identitySeed =
                "verified-recovery|" + scope.stableKey() + "|" + claimKey
        )
    }

    /**
     * Explicit user contribution API. Callers must use this only for a direct
     * user instruction/constraint; this function does not infer constraints
     * from arbitrary chat text.
     */
    fun explicitUserConstraint(
        task: TaskState,
        sourceId: String,
        claimKey: String,
        statement: String,
        rationale: String,
        at: Long,
        stance: ConstitutionStance = ConstitutionStance.AFFIRM
    ): ConstitutionRule =
        ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.USER,
                sourceId = safeId(sourceId),
                projectId = task.projectId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::safeId),
                taskId = safeId(task.id),
                at = at
            ),
            claimKey = safeClaimKey(claimKey),
            stance = stance,
            kind = ConstitutionRuleKind.USER_CONSTRAINT,
            statement = safeText(statement, 1_400),
            rationale = safeText(rationale, 2_000),
            scope = scopeForTask(task)
        )

    /**
     * Explicit model contribution API. Model observations are deliberately
     * non-active until separately matched to verified local evidence.
     */
    fun modelObservation(
        task: TaskState,
        modelId: String,
        sourceId: String,
        claimKey: String,
        kind: ConstitutionRuleKind,
        statement: String,
        rationale: String,
        at: Long,
        stance: ConstitutionStance = ConstitutionStance.AFFIRM
    ): ConstitutionRule {
        require(kind != ConstitutionRuleKind.USER_CONSTRAINT) {
            "A model cannot create a USER_CONSTRAINT"
        }
        return ConstitutionGenomePolicy.propose(
            source = ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.MODEL,
                sourceId = safeId(sourceId),
                modelId = safeId(modelId),
                projectId = task.projectId
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::safeId),
                taskId = safeId(task.id),
                at = at
            ),
            claimKey = safeClaimKey(claimKey),
            stance = stance,
            kind = kind,
            statement = safeText(statement, 1_400),
            rationale = safeText(rationale, 2_000),
            scope = scopeForTask(task)
        )
    }

    fun scopeForTask(task: TaskState): ConstitutionScope =
        task.projectId
            ?.takeIf { it.isNotBlank() }
            ?.let {
                ConstitutionScope(
                    kind = ConstitutionScopeKind.PROJECT,
                    key = safeId(it)
                )
            }
            ?: ConstitutionScope(
                kind = ConstitutionScopeKind.PROJECT,
                key = "task:" + safeId(task.id)
            )

    private fun safeClaimKey(value: String): String {
        val clean = value
            .lowercase()
            .replace(Regex("[^a-z0-9._:-]+"), "-")
            .trim('-')
            .take(160)
        require(clean.isNotBlank()) {
            "Constitution claimKey must contain a stable identifier"
        }
        return clean
    }

    private fun safeText(
        value: String,
        maxChars: Int
    ): String {
        val clean = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
            .take(maxChars)
        require(clean.isNotBlank())
        return clean
    }

    private fun safeId(value: String): String {
        val clean = value
            .replace('\u0000', ' ')
            .replace(Regex("[\\r\\n\\t]+"), " ")
            .trim()
        if (clean.matches(Regex("[A-Za-z0-9._:-]{1,160}"))) {
            return clean
        }
        return "id-" + sha256(clean).take(24)
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
