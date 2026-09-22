package com.lumena.android.agent.core

import java.security.MessageDigest

enum class ConstitutionSourceKind {
    SYSTEM_SEED,
    USER,
    MODEL,
    TOOL_RESULT,
    PROJECT_TEST,
    PROJECT_ARTIFACT,
    IMPORTED_SOURCE_DEVICE
}

enum class ConstitutionScopeKind {
    GLOBAL,
    PROJECT,
    DEVICE,
    MODEL
}

data class ConstitutionScope(
    val kind: ConstitutionScopeKind,
    val key: String
) {
    init {
        require(key.isNotBlank())
        require(key.length <= 160)
    }

    fun stableKey(): String = "${kind.name}:$key"
}

enum class ConstitutionRuleKind {
    USER_CONSTRAINT,
    STRATEGY,
    RECOVERY,
    VERIFICATION,
    SAFETY
}

enum class ConstitutionRuleStatus {
    OBSERVATION,
    CANDIDATE,
    LEARNED,
    ACTIVE_USER_CONSTRAINT,
    CONTESTED,
    SUPERSEDED,
    HARD_INVARIANT
}

enum class ConstitutionAuthority {
    ADVISORY,
    USER_CONSTRAINT,
    HARD_GUARD
}

enum class ConstitutionStance {
    AFFIRM,
    REJECT
}

enum class ConstitutionEvidenceKind {
    TOOL_RESULT,
    TEST_RESULT,
    USER_DIRECTIVE,
    PROJECT_ARTIFACT,
    IMPORTED_SOURCE_DEVICE,
    MODEL_TEXT
}

data class ConstitutionEvidenceRef(
    val id: String,
    val kind: ConstitutionEvidenceKind,
    val locallyVerified: Boolean,
    val taskId: String? = null,
    val projectId: String? = null,
    val at: Long
) {
    init {
        require(id.isNotBlank())
        require(id.length <= 180)
        require(at > 0)
        require(taskId == null || taskId.length <= 160)
        require(projectId == null || projectId.length <= 160)
        if (
            kind == ConstitutionEvidenceKind.IMPORTED_SOURCE_DEVICE ||
            kind == ConstitutionEvidenceKind.MODEL_TEXT
        ) {
            require(!locallyVerified) {
                "$kind cannot be marked as locally verified evidence"
            }
        }
    }

    fun promotionEligible(): Boolean =
        locallyVerified &&
            kind in setOf(
                ConstitutionEvidenceKind.TOOL_RESULT,
                ConstitutionEvidenceKind.TEST_RESULT,
                ConstitutionEvidenceKind.PROJECT_ARTIFACT
            )

    fun contextKey(): String =
        taskId?.takeIf { it.isNotBlank() }?.let { "task:$it" }
            ?: projectId?.takeIf { it.isNotBlank() }?.let { "project:$it" }
            ?: "evidence:$id"
}

data class ConstitutionProvenance(
    val sourceKind: ConstitutionSourceKind,
    val sourceId: String,
    val modelId: String? = null,
    val projectId: String? = null,
    val taskId: String? = null,
    val at: Long
) {
    init {
        require(sourceId.isNotBlank())
        require(sourceId.length <= 180)
        require(modelId == null || modelId.length <= 180)
        require(projectId == null || projectId.length <= 160)
        require(taskId == null || taskId.length <= 160)
        require(at > 0)
    }
}

data class ConstitutionRule(
    val id: String,
    val claimKey: String,
    val stance: ConstitutionStance,
    val kind: ConstitutionRuleKind,
    val statement: String,
    val rationale: String,
    val threatPrevented: String,
    val scope: ConstitutionScope,
    val authority: ConstitutionAuthority,
    val status: ConstitutionRuleStatus,
    val provenance: List<ConstitutionProvenance>,
    val evidenceRefs: List<ConstitutionEvidenceRef>,
    val testRefs: List<String>,
    val enforcementPoints: List<String>,
    val supersedes: List<String>,
    val createdAt: Long,
    val updatedAt: Long,
    val revision: Int = 1
) {
    init {
        require(id.isNotBlank())
        require(id.length <= 128)
        require(claimKey.isNotBlank())
        require(claimKey.length <= 160)
        require(statement.isNotBlank())
        require(statement.length <= 1_400)
        require(rationale.isNotBlank())
        require(rationale.length <= 2_000)
        require(threatPrevented.length <= 1_400)
        require(createdAt > 0)
        require(updatedAt >= createdAt)
        require(revision >= 1)
        require(testRefs.size <= 32)
        require(enforcementPoints.size <= 32)
        require(supersedes.size <= 32)

        if (authority == ConstitutionAuthority.HARD_GUARD) {
            require(status == ConstitutionRuleStatus.HARD_INVARIANT)
            require(provenance.any { it.sourceKind == ConstitutionSourceKind.SYSTEM_SEED })
            require(testRefs.isNotEmpty())
            require(enforcementPoints.isNotEmpty())
        }

        if (status == ConstitutionRuleStatus.HARD_INVARIANT) {
            require(authority == ConstitutionAuthority.HARD_GUARD)
        }

        if (authority == ConstitutionAuthority.USER_CONSTRAINT) {
            require(kind == ConstitutionRuleKind.USER_CONSTRAINT)
            require(provenance.any { it.sourceKind == ConstitutionSourceKind.USER })
        }
    }

    fun key(): String = "${scope.stableKey()}|$claimKey"
}

data class ConstitutionGenomeState(
    val schemaVersion: Int = ConstitutionGenomePolicy.SCHEMA_VERSION,
    val rules: List<ConstitutionRule> = emptyList(),
    val revision: Long = 0
)

data class ConstitutionGenomeView(
    val rules: List<ConstitutionRule>,
    val conflicts: List<ConstitutionConflict>
)

data class ConstitutionConflict(
    val claimKey: String,
    val scope: ConstitutionScope,
    val ruleIds: List<String>,
    val hasHardInvariant: Boolean
)

object ConstitutionGenomePolicy {
    const val SCHEMA_VERSION = 1
    const val MIN_VERIFIED_EVIDENCE = 3
    const val MIN_DISTINCT_CONTEXTS = 2
    const val MAX_RULES = 1_024
    const val MAX_PROVENANCE = 32
    const val MAX_EVIDENCE = 128

    fun seedHardInvariant(
        id: String,
        claimKey: String,
        kind: ConstitutionRuleKind,
        statement: String,
        rationale: String,
        threatPrevented: String,
        testRefs: List<String>,
        enforcementPoints: List<String>,
        createdAt: Long
    ): ConstitutionRule = ConstitutionRule(
        id = id,
        claimKey = claimKey,
        stance = ConstitutionStance.AFFIRM,
        kind = kind,
        statement = statement,
        rationale = rationale,
        threatPrevented = threatPrevented,
        scope = ConstitutionScope(
            kind = ConstitutionScopeKind.GLOBAL,
            key = "lumena"
        ),
        authority = ConstitutionAuthority.HARD_GUARD,
        status = ConstitutionRuleStatus.HARD_INVARIANT,
        provenance = listOf(
            ConstitutionProvenance(
                sourceKind = ConstitutionSourceKind.SYSTEM_SEED,
                sourceId = "core-dna:${CoreDna.VERSION}",
                at = createdAt
            )
        ),
        evidenceRefs = emptyList(),
        testRefs = testRefs.distinct().take(32),
        enforcementPoints = enforcementPoints.distinct().take(32),
        supersedes = emptyList(),
        createdAt = createdAt,
        updatedAt = createdAt
    )

    fun propose(
        source: ConstitutionProvenance,
        claimKey: String,
        stance: ConstitutionStance,
        kind: ConstitutionRuleKind,
        statement: String,
        rationale: String,
        threatPrevented: String = "",
        scope: ConstitutionScope,
        evidenceRefs: List<ConstitutionEvidenceRef> = emptyList(),
        testRefs: List<String> = emptyList(),
        enforcementPoints: List<String> = emptyList()
    ): ConstitutionRule {
        require(source.sourceKind != ConstitutionSourceKind.SYSTEM_SEED) {
            "Hard/system invariants must be created through seedHardInvariant"
        }

        val authority = when {
            source.sourceKind == ConstitutionSourceKind.USER &&
                kind == ConstitutionRuleKind.USER_CONSTRAINT ->
                ConstitutionAuthority.USER_CONSTRAINT
            else -> ConstitutionAuthority.ADVISORY
        }

        val initialStatus = when {
            authority == ConstitutionAuthority.USER_CONSTRAINT ->
                ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT
            source.sourceKind == ConstitutionSourceKind.MODEL ->
                ConstitutionRuleStatus.OBSERVATION
            source.sourceKind == ConstitutionSourceKind.IMPORTED_SOURCE_DEVICE ->
                ConstitutionRuleStatus.OBSERVATION
            else ->
                ConstitutionRuleStatus.CANDIDATE
        }

        val createdAt = source.at
        val rule = ConstitutionRule(
            id = ruleId(
                claimKey = claimKey,
                stance = stance,
                scope = scope,
                sourceId = source.sourceId,
                statement = statement
            ),
            claimKey = claimKey,
            stance = stance,
            kind = kind,
            statement = statement,
            rationale = rationale,
            threatPrevented = threatPrevented,
            scope = scope,
            authority = authority,
            status = initialStatus,
            provenance = listOf(source),
            evidenceRefs = evidenceRefs.distinctBy { it.id }.take(MAX_EVIDENCE),
            testRefs = testRefs.distinct().take(32),
            enforcementPoints = enforcementPoints.distinct().take(32),
            supersedes = emptyList(),
            createdAt = createdAt,
            updatedAt = createdAt
        )

        return evaluate(rule)
    }

    fun add(
        state: ConstitutionGenomeState,
        rule: ConstitutionRule
    ): ConstitutionGenomeState {
        require(state.schemaVersion == SCHEMA_VERSION)
        val withoutSameId = state.rules.filterNot { it.id == rule.id }
        val nextRules = (withoutSameId + rule)
            .sortedWith(
                compareBy<ConstitutionRule> { it.createdAt }
                    .thenBy { it.id }
            )
            .takeLast(MAX_RULES)

        return reconcile(
            state.copy(
                rules = nextRules,
                revision = state.revision + 1
            )
        )
    }

    fun recordEvidence(
        state: ConstitutionGenomeState,
        ruleId: String,
        evidence: ConstitutionEvidenceRef,
        provenance: ConstitutionProvenance
    ): ConstitutionGenomeState {
        val current = state.rules.firstOrNull { it.id == ruleId }
            ?: return state

        if (current.status == ConstitutionRuleStatus.SUPERSEDED) {
            return state
        }

        if (current.status == ConstitutionRuleStatus.HARD_INVARIANT) {
            // Evidence may explain/trace the invariant, but cannot mutate its authority.
            val updated = current.copy(
                evidenceRefs = (current.evidenceRefs + evidence)
                    .distinctBy { it.id }
                    .takeLast(MAX_EVIDENCE),
                provenance = (current.provenance + provenance)
                    .distinctBy { "${it.sourceKind}|${it.sourceId}|${it.at}" }
                    .takeLast(MAX_PROVENANCE),
                updatedAt = maxOf(current.updatedAt, evidence.at, provenance.at),
                revision = current.revision + 1
            )
            return replace(state, updated)
        }

        val updated = current.copy(
            evidenceRefs = (current.evidenceRefs + evidence)
                .distinctBy { it.id }
                .takeLast(MAX_EVIDENCE),
            provenance = (current.provenance + provenance)
                .distinctBy { "${it.sourceKind}|${it.sourceId}|${it.at}" }
                .takeLast(MAX_PROVENANCE),
            updatedAt = maxOf(current.updatedAt, evidence.at, provenance.at),
            revision = current.revision + 1
        )

        return replace(state, evaluate(updated))
    }

    fun supersedeAdvisory(
        state: ConstitutionGenomeState,
        oldRuleId: String,
        replacementRuleId: String,
        at: Long
    ): ConstitutionGenomeState {
        require(at > 0)
        val old = state.rules.firstOrNull { it.id == oldRuleId } ?: return state
        val replacement = state.rules.firstOrNull { it.id == replacementRuleId } ?: return state

        require(old.authority != ConstitutionAuthority.HARD_GUARD) {
            "Hard invariants cannot be superseded by learned/advisory rules"
        }
        require(old.key() == replacement.key()) {
            "Superseding rules must address the same claim and scope"
        }
        require(replacement.status != ConstitutionRuleStatus.SUPERSEDED)

        val updatedOld = old.copy(
            status = ConstitutionRuleStatus.SUPERSEDED,
            updatedAt = maxOf(old.updatedAt, at),
            revision = old.revision + 1
        )
        val updatedReplacement = replacement.copy(
            supersedes = (replacement.supersedes + old.id)
                .distinct()
                .take(32),
            updatedAt = maxOf(replacement.updatedAt, at),
            revision = replacement.revision + 1
        )

        var next = replace(state, updatedOld)
        next = replace(next, updatedReplacement)
        return reconcile(next)
    }

    fun view(state: ConstitutionGenomeState): ConstitutionGenomeView {
        val reconciled = reconcile(state)
        val conflicts = reconciled.rules
            .filter { it.status != ConstitutionRuleStatus.SUPERSEDED }
            .groupBy { it.key() }
            .mapNotNull { (_, sameClaim) ->
                val stances = sameClaim.map { it.stance }.toSet()
                if (stances.size < 2) return@mapNotNull null
                ConstitutionConflict(
                    claimKey = sameClaim.first().claimKey,
                    scope = sameClaim.first().scope,
                    ruleIds = sameClaim.map { it.id }.sorted(),
                    hasHardInvariant = sameClaim.any {
                        it.status == ConstitutionRuleStatus.HARD_INVARIANT
                    }
                )
            }
            .sortedBy { "${it.scope.stableKey()}|${it.claimKey}" }

        return ConstitutionGenomeView(
            rules = reconciled.rules,
            conflicts = conflicts
        )
    }

    fun effectiveRules(
        state: ConstitutionGenomeState,
        scope: ConstitutionScope
    ): List<ConstitutionRule> {
        val view = view(state)
        val conflictedIds = view.conflicts.flatMap { it.ruleIds }.toSet()

        return view.rules
            .filter { it.id !in conflictedIds }
            .filter { rule ->
                rule.scope == scope ||
                    rule.scope.kind == ConstitutionScopeKind.GLOBAL
            }
            .filter {
                it.status in setOf(
                    ConstitutionRuleStatus.HARD_INVARIANT,
                    ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT,
                    ConstitutionRuleStatus.LEARNED
                )
            }
            .sortedWith(
                compareByDescending<ConstitutionRule> {
                    when (it.authority) {
                        ConstitutionAuthority.HARD_GUARD -> 3
                        ConstitutionAuthority.USER_CONSTRAINT -> 2
                        ConstitutionAuthority.ADVISORY -> 1
                    }
                }.thenBy { it.claimKey }
            )
    }

    fun canPromoteToLearned(rule: ConstitutionRule): Boolean {
        if (rule.authority != ConstitutionAuthority.ADVISORY) return false
        if (
            rule.status == ConstitutionRuleStatus.HARD_INVARIANT ||
            rule.status == ConstitutionRuleStatus.SUPERSEDED
        ) {
            return false
        }

        val verified = rule.evidenceRefs
            .filter { it.promotionEligible() }
            .distinctBy { it.id }

        val contexts = verified
            .map { it.contextKey() }
            .distinct()

        return verified.size >= MIN_VERIFIED_EVIDENCE &&
            contexts.size >= MIN_DISTINCT_CONTEXTS
    }

    private fun evaluate(rule: ConstitutionRule): ConstitutionRule {
        if (
            rule.status == ConstitutionRuleStatus.HARD_INVARIANT ||
            rule.status == ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT ||
            rule.status == ConstitutionRuleStatus.SUPERSEDED
        ) {
            return rule
        }

        val nextStatus = if (canPromoteToLearned(rule)) {
            ConstitutionRuleStatus.LEARNED
        } else {
            when {
                rule.provenance.any {
                    it.sourceKind == ConstitutionSourceKind.MODEL ||
                        it.sourceKind == ConstitutionSourceKind.IMPORTED_SOURCE_DEVICE
                } &&
                    rule.evidenceRefs.none { it.promotionEligible() } ->
                    ConstitutionRuleStatus.OBSERVATION

                else -> ConstitutionRuleStatus.CANDIDATE
            }
        }

        return if (nextStatus == rule.status) rule else rule.copy(
            status = nextStatus,
            revision = rule.revision + 1
        )
    }

    private fun reconcile(
        state: ConstitutionGenomeState
    ): ConstitutionGenomeState {
        val active = state.rules
            .filter { it.status != ConstitutionRuleStatus.SUPERSEDED }
            .groupBy { it.key() }

        val conflicted = active
            .filterValues { rules -> rules.map { it.stance }.toSet().size > 1 }
            .values
            .flatten()
            .map { it.id }
            .toSet()

        val hardByKey = active
            .mapValues { (_, rules) ->
                rules.filter { it.status == ConstitutionRuleStatus.HARD_INVARIANT }
            }

        val next = state.rules.map { rule ->
            if (rule.status == ConstitutionRuleStatus.SUPERSEDED) {
                rule
            } else if (
                rule.id in conflicted &&
                rule.status != ConstitutionRuleStatus.HARD_INVARIANT
            ) {
                rule.copy(
                    status = ConstitutionRuleStatus.CONTESTED
                )
            } else if (
                hardByKey[rule.key()].orEmpty().any {
                    it.stance != rule.stance
                } &&
                rule.status != ConstitutionRuleStatus.HARD_INVARIANT
            ) {
                rule.copy(
                    status = ConstitutionRuleStatus.CONTESTED
                )
            } else {
                evaluate(rule)
            }
        }

        return if (next == state.rules) state else state.copy(rules = next)
    }

    private fun replace(
        state: ConstitutionGenomeState,
        rule: ConstitutionRule
    ): ConstitutionGenomeState {
        val next = state.rules.map {
            if (it.id == rule.id) rule else it
        }
        if (next == state.rules) return state
        return reconcile(
            state.copy(
                rules = next,
                revision = state.revision + 1
            )
        )
    }

    private fun ruleId(
        claimKey: String,
        stance: ConstitutionStance,
        scope: ConstitutionScope,
        sourceId: String,
        statement: String
    ): String = "cg-" + sha256(
        listOf(
            claimKey.trim(),
            stance.name,
            scope.stableKey(),
            sourceId.trim(),
            statement.trim()
        ).joinToString("|")
    ).take(24)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

/**
 * Built-in rationale for constitutional rules that already exist in executable
 * Lumena control paths. These entries are explanatory mirrors of executable
 * guards, not a replacement for them.
 */
object ConstitutionDnaManifest {
    const val VERSION = "lumena-dna-rationale-v1"
    private const val SEED_AT = 1L

    fun hardInvariants(): List<ConstitutionRule> = listOf(
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-EVIDENCE-001",
            claimKey = "execution-proof",
            kind = ConstitutionRuleKind.VERIFICATION,
            statement = "TOOL_RESULT is execution evidence; model prose is not proof of execution or task completion.",
            rationale = "A language model can describe an action without that action having happened. Keeping execution proof tied to observed tool results prevents invented completion state.",
            threatPrevented = "Fabricated success, stale assumptions, and completion claims without an observed external effect.",
            testRefs = listOf(
                "AgentControllerTest.failedToolProducesRecoveryGuidanceInDynamicContext",
                "WorkflowRunnerConstitutionTest.knownActionToolEnvelopeExecutesBeforeAnyCorrectionTurn"
            ),
            enforcementPoints = listOf(
                "AgentController",
                "ContextKernel",
                "WorkflowRunner"
            ),
            createdAt = SEED_AT
        ),
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-AUTHORITY-001",
            claimKey = "learned-authority-boundary",
            kind = ConstitutionRuleKind.SAFETY,
            statement = "Model output, learned memory, imported experience and reflex advice cannot grant permissions or bypass ToolRegistry, ToolGate, or confirmation.",
            rationale = "Experience may improve choice quality, but allowing learned content to create authority would let stale, imported, or model-generated text silently enlarge execution permissions.",
            threatPrevented = "Self-authorizing behavior, prompt-injection authority escalation, and cross-device permission transfer.",
            testRefs = listOf(
                "ConstitutionKernelTest.policyDenialNeverFallsThroughToAlternativeRoute",
                "ReflexKernelTest.rankRejectsOptionOutsideConstitutionalCandidateSet",
                "WorkflowRunnerTest.illegalReflexOptionIsRejectedAndNeverBecomesExecution"
            ),
            enforcementPoints = listOf(
                "ToolRegistry",
                "ToolGate",
                "ConstitutionKernel",
                "ReflexKernel"
            ),
            createdAt = SEED_AT
        ),
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-UNKNOWN-EFFECT-001",
            claimKey = "unknown-effect-replay",
            kind = ConstitutionRuleKind.SAFETY,
            statement = "A mutating or executable action with an unknown outcome is never replayed automatically.",
            rationale = "After transport loss, the mutation may already have happened. Replaying without rediscovering state can duplicate or corrupt the external effect.",
            threatPrevented = "Duplicate writes, duplicate commands, and irreversible side effects after ambiguous transport failure.",
            testRefs = listOf(
                "ConstitutionKernelTest.unknownMutationOutcomeNeverReplays",
                "WorkflowRunnerTest.unknownMutationOutcomeStopsBeforeReflexProviderAndNeverReplays"
            ),
            enforcementPoints = listOf(
                "FailureEvent",
                "ConstitutionKernel",
                "AgentController"
            ),
            createdAt = SEED_AT
        ),
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-REFLEX-001",
            claimKey = "reflex-authority-monotonicity",
            kind = ConstitutionRuleKind.SAFETY,
            statement = "ReflexKernel may rank only options already admitted by ConstitutionKernel and cannot execute a tool directly.",
            rationale = "A fast decision layer is useful only if it remains inside the same authority boundary as the slower planner.",
            threatPrevented = "Fast-path permission bypass and unsafe escalation from advisory evidence to execution authority.",
            testRefs = listOf(
                "ReflexKernelTest.stateDriftCannotBeEscalatedIntoRetryVariant",
                "ReflexKernelTest.unknownEffectMutationAllowsStopOnly",
                "WorkflowRunnerTest.illegalReflexOptionIsRejectedAndNeverBecomesExecution"
            ),
            enforcementPoints = listOf(
                "ConstitutionKernel",
                "ReflexKernel",
                "WorkflowRunner"
            ),
            createdAt = SEED_AT
        ),
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-LEARNING-001",
            claimKey = "learned-to-hard-promotion",
            kind = ConstitutionRuleKind.SAFETY,
            statement = "Learned rules and user/project experience cannot automatically promote themselves into HARD_GUARD authority.",
            rationale = "Repeated success can justify an advisory strategy, but hard constitutional authority requires an explicit code-reviewed system seed with enforcement and regression tests.",
            threatPrevented = "Self-modifying constitutional authority caused by repeated but narrow or poisoned experience.",
            testRefs = listOf(
                "ConstitutionGenomePolicyTest.learnedRuleNeverBecomesHardGuard",
                "ConstitutionGenomePolicyTest.modelProposalCannotPromoteWithoutVerifiedEvidence"
            ),
            enforcementPoints = listOf(
                "ConstitutionGenomePolicy.seedHardInvariant",
                "ConstitutionGenomePolicy.canPromoteToLearned"
            ),
            createdAt = SEED_AT
        ),
        ConstitutionGenomePolicy.seedHardInvariant(
            id = "INV-PORTABLE-001",
            claimKey = "portable-local-revalidation",
            kind = ConstitutionRuleKind.VERIFICATION,
            statement = "Portable source-device experience remains advisory until locally revalidated on the receiving device.",
            rationale = "A successful path on another device, model, bridge version or project state is evidence from a different environment, not proof of current local behavior.",
            threatPrevented = "Cross-device stale assumptions and imported experience being mistaken for local execution proof.",
            testRefs = listOf(
                "PortableKernelBundleTest.portableExecutionExampleAdviceIsExplicitlyAdvisory",
                "PortableKernelBundleTest.schemaOneBundleRemainsReadableAfterSchemaTwoUpgrade"
            ),
            enforcementPoints = listOf(
                "PortableKernelPolicy",
                "PortableKernelStore",
                "ExperienceMemoryStore"
            ),
            createdAt = SEED_AT
        )
    )

    fun promptSummary(): String = buildString {
        appendLine("DNA RATIONALE $VERSION")
        hardInvariants().forEach { rule ->
            append(rule.id)
            append(": ")
            append(rule.statement)
            append(" WHY: ")
            append(rule.rationale)
            appendLine()
        }
    }.trimEnd()
}
