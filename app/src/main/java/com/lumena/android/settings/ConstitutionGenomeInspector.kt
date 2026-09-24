package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionAuthority
import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionProvenance
import com.lumena.android.agent.core.ConstitutionRule
import com.lumena.android.agent.core.ConstitutionRuleStatus

data class ConstitutionInspectorEntry(
    val id: String,
    val claimKey: String,
    val stance: String,
    val kind: String,
    val status: String,
    val authority: String,
    val statement: String,
    val rationale: String,
    val threatPrevented: String,
    val scope: String,
    val localEvidenceCount: Int,
    val distinctLocalContexts: Int,
    val distinctTaskCount: Int,
    val distinctProjectCount: Int,
    val geneStage: String,
    val activationProgressPercent: Int,
    val pairedProjectContexts: Int,
    val contributorModelIds: List<String>,
    val provenance: List<String>,
    val enforcementPoints: List<String>,
    val regressionTests: List<String>,
    val explanation: String
)

data class ConstitutionInspectorConflictRule(
    val id: String,
    val stance: String,
    val authority: String,
    val status: String,
    val sourceKinds: List<String>
)

data class ConstitutionInspectorConflict(
    val claimKey: String,
    val scope: String,
    val hasHardInvariant: Boolean,
    val rules: List<ConstitutionInspectorConflictRule>
)

data class ImportedConstitutionInspectorEntry(
    val id: String,
    val claimKey: String,
    val stance: String,
    val kind: String,
    val sourceStatus: String,
    val originAuthority: String,
    val sourceScopeHash: String,
    val sourceDeviceHash: String,
    val statement: String,
    val rationale: String,
    val sourceEvidenceCount: Int,
    val contributorModelIds: List<String>,
    val activationRequirement: String,
    val explanation: String
)

data class ConstitutionInspectorSnapshot(
    val hardDna: List<ConstitutionInspectorEntry>,
    val learned: List<ConstitutionInspectorEntry>,
    val shadowCandidates: List<ConstitutionInspectorEntry>,
    val userConstraints: List<ConstitutionInspectorEntry>,
    val contested: List<ConstitutionInspectorConflict>,
    val importedLearned: List<ImportedConstitutionInspectorEntry>,
    val importedUserConstraints: List<ImportedConstitutionInspectorEntry>
)

object ConstitutionGenomeInspectorPolicy {
    fun build(
        localState: ConstitutionGenomeState,
        imported: PortableKernelPayload?
    ): ConstitutionInspectorSnapshot {
        val hydrated = ConstitutionGenomeRuntime.hydrate(localState)
        val view = ConstitutionGenomePolicy.view(hydrated)

        val hard = view.rules
            .filter {
                it.status == ConstitutionRuleStatus.HARD_INVARIANT &&
                    it.authority == ConstitutionAuthority.HARD_GUARD
            }
            .sortedBy { it.id }
            .map(::localEntry)

        val learned = view.rules
            .filter {
                it.status == ConstitutionRuleStatus.LEARNED &&
                    it.authority == ConstitutionAuthority.ADVISORY
            }
            .sortedWith(
                compareBy<ConstitutionRule> { it.scope.stableKey() }
                    .thenBy { it.claimKey }
                    .thenBy { it.id }
            )
            .map(::localEntry)

        val shadowCandidates = view.rules
            .filter {
                it.status == ConstitutionRuleStatus.CANDIDATE &&
                    it.authority == ConstitutionAuthority.ADVISORY
            }
            .sortedWith(
                compareBy<ConstitutionRule> { it.scope.stableKey() }
                    .thenBy { it.claimKey }
                    .thenBy { it.id }
            )
            .map(::localEntry)

        val users = view.rules
            .filter {
                it.status == ConstitutionRuleStatus.ACTIVE_USER_CONSTRAINT &&
                    it.authority == ConstitutionAuthority.USER_CONSTRAINT
            }
            .sortedWith(
                compareBy<ConstitutionRule> { it.scope.stableKey() }
                    .thenBy { it.claimKey }
                    .thenBy { it.id }
            )
            .map(::localEntry)

        val conflicts = view.conflicts.map { conflict ->
            val competing = view.rules
                .filter {
                    it.status != ConstitutionRuleStatus.SUPERSEDED &&
                        it.scope == conflict.scope &&
                        it.claimKey == conflict.claimKey
                }
                .sortedWith(
                    compareByDescending<ConstitutionRule> {
                        authorityRank(it.authority)
                    }.thenBy { it.stance.name }
                        .thenBy { it.id }
                )
                .map { rule ->
                    ConstitutionInspectorConflictRule(
                        id = rule.id,
                        stance = rule.stance.name,
                        authority = rule.authority.name,
                        status = rule.status.name,
                        sourceKinds = rule.provenance
                            .map { it.sourceKind.name }
                            .distinct()
                            .sorted()
                    )
                }

            ConstitutionInspectorConflict(
                claimKey = conflict.claimKey,
                scope = conflict.scope.stableKey(),
                hasHardInvariant = conflict.hasHardInvariant,
                rules = competing
            )
        }

        val importedEntries = imported
            ?.constitutionalSeeds
            .orEmpty()
            .sortedWith(
                compareByDescending<PortableConstitutionSeed> { it.updatedAt }
                    .thenBy { it.id }
            )
            .map { seed ->
                ImportedConstitutionInspectorEntry(
                    id = seed.id,
                    claimKey = seed.claimKey,
                    stance = seed.stance,
                    kind = seed.kind,
                    sourceStatus = seed.sourceStatus,
                    originAuthority = seed.originAuthority,
                    sourceScopeHash = seed.sourceScopeHash,
                    sourceDeviceHash = imported?.sourceDeviceHash.orEmpty(),
                    statement = seed.statement,
                    rationale = seed.rationale,
                    sourceEvidenceCount = seed.evidenceIds.distinct().size,
                    contributorModelIds = seed.contributorModelIds.distinct(),
                    activationRequirement = seed.activationRequirement,
                    explanation = if (
                        seed.originAuthority ==
                        ConstitutionAuthority.USER_CONSTRAINT.name
                    ) {
                        "Source-device user constraint record. It is not active locally until the user explicitly reconfirms it; it is not permission and cannot create HARD_GUARD authority."
                    } else {
                        "Source-device learned advisory. Source evidence is provenance only and does not count as local proof; local promotion starts again from verified local evidence."
                    }
                )
            }

        return ConstitutionInspectorSnapshot(
            hardDna = hard,
            learned = learned,
            shadowCandidates = shadowCandidates,
            userConstraints = users,
            contested = conflicts,
            importedLearned = importedEntries.filter {
                it.originAuthority == ConstitutionAuthority.ADVISORY.name
            },
            importedUserConstraints = importedEntries.filter {
                it.originAuthority ==
                    ConstitutionAuthority.USER_CONSTRAINT.name
            }
        )
    }

    private fun localEntry(
        rule: ConstitutionRule
    ): ConstitutionInspectorEntry {
        val calibration =
            ConstitutionGenomePolicy.calibration(rule)
        val eligible = rule.evidenceRefs
            .filter { it.promotionEligible() }
            .distinctBy { it.id }
        val contexts = eligible
            .map { it.contextKey() }
            .distinct()
        val distinctTasks = eligible
            .mapNotNull { it.taskId }
            .filter { it.isNotBlank() }
            .distinct()
        val distinctProjects = eligible
            .mapNotNull { it.projectId }
            .filter { it.isNotBlank() }
            .distinct()

        val explanation = when {
            rule.authority == ConstitutionAuthority.HARD_GUARD ->
                "Code-owned HARD_GUARD. Its authority comes from the current APK seed and executable enforcement points, not from persisted or imported memory."

            rule.authority == ConstitutionAuthority.USER_CONSTRAINT ->
                "Explicit local user directive. It is active as USER_CONSTRAINT but never becomes HARD_GUARD."

            rule.status == ConstitutionRuleStatus.LEARNED ->
                "ACTIVE learned gene. Promoted after ${eligible.size} locally verified evidence references across ${contexts.size} distinct local task/project contexts; still advisory and not permission."

            rule.status == ConstitutionRuleStatus.CANDIDATE ->
                "SHADOW candidate at ${calibration.activationProgressPercent}% activation progress. It is visible for inspection but is not injected as an active learned Constitution rule."

            else ->
                "Non-authoritative constitutional record; inspect its status and provenance before reuse."
        }

        return ConstitutionInspectorEntry(
            id = rule.id,
            claimKey = rule.claimKey,
            stance = rule.stance.name,
            kind = rule.kind.name,
            status = rule.status.name,
            authority = rule.authority.name,
            statement = rule.statement,
            rationale = rule.rationale,
            threatPrevented = rule.threatPrevented,
            scope = rule.scope.stableKey(),
            localEvidenceCount = eligible.size,
            distinctLocalContexts = contexts.size,
            distinctTaskCount = distinctTasks.size,
            distinctProjectCount = distinctProjects.size,
            geneStage = calibration.stage.name,
            activationProgressPercent =
                calibration.activationProgressPercent,
            pairedProjectContexts =
                calibration.pairedProjectContextCount,
            contributorModelIds = rule.provenance
                .mapNotNull { it.modelId }
                .distinct()
                .sorted(),
            provenance = rule.provenance
                .distinctBy(::provenanceKey)
                .sortedBy { it.at }
                .map(::formatProvenance),
            enforcementPoints = rule.enforcementPoints.distinct(),
            regressionTests = rule.testRefs.distinct(),
            explanation = explanation
        )
    }

    private fun provenanceKey(
        value: ConstitutionProvenance
    ): String =
        listOf(
            value.sourceKind.name,
            value.sourceId,
            value.modelId.orEmpty(),
            value.projectId.orEmpty(),
            value.taskId.orEmpty(),
            value.at.toString()
        ).joinToString("|")

    private fun formatProvenance(
        value: ConstitutionProvenance
    ): String = buildString {
        append(value.sourceKind.name)
        append(" · ")
        append(value.sourceId)
        value.modelId?.let {
            append(" · model=")
            append(it)
        }
        value.projectId?.let {
            append(" · project=")
            append(it)
        }
        value.taskId?.let {
            append(" · task=")
            append(it)
        }
        append(" · at=")
        append(value.at)
    }

    private fun authorityRank(
        authority: ConstitutionAuthority
    ): Int = when (authority) {
        ConstitutionAuthority.HARD_GUARD -> 3
        ConstitutionAuthority.USER_CONSTRAINT -> 2
        ConstitutionAuthority.ADVISORY -> 1
    }
}
