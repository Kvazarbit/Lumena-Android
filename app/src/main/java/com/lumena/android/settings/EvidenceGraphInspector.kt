package com.lumena.android.settings

import com.lumena.android.agent.core.EvidenceClaimNode
import com.lumena.android.agent.core.EvidenceGraphReducer
import com.lumena.android.agent.core.EvidenceGraphState
import com.lumena.android.agent.core.EvidenceProjectOutcome
import com.lumena.android.agent.core.EvidenceSourceNode
import com.lumena.android.agent.core.EvidenceVerificationState

data class EvidenceInspectorSource(
    val id: String,
    val uri: String,
    val host: String,
    val kind: String,
    val retrievalMethod: String,
    val evidenceCount: Int,
    val firstObservedAt: Long,
    val lastObservedAt: Long
)

data class EvidenceInspectorClaim(
    val id: String,
    val claimKey: String,
    val statement: String,
    val storedState: String,
    val effectiveState: String,
    val supportSources: List<EvidenceInspectorSource>,
    val contradictionSources: List<EvidenceInspectorSource>,
    val mentionSources: List<EvidenceInspectorSource>,
    val evidenceCount: Int,
    val projectId: String?,
    val projectRelevancePercent: Int,
    val outcome: String,
    val outcomeEvidenceCount: Int,
    val explanation: String
)

data class EvidenceInspectorSnapshot(
    val totalClaims: Int,
    val totalSources: Int,
    val discovered: Int,
    val retrieved: Int,
    val corroborated: Int,
    val contested: Int,
    val staleEffective: Int,
    val claims: List<EvidenceInspectorClaim>
)

/**
 * Read-only projection for explaining the typed Evidence Graph.
 *
 * The inspector never mutates graph state and never turns evidence into
 * execution authority. It exposes the distinction between stored verification
 * and time-dependent effective staleness.
 */
object EvidenceGraphInspectorPolicy {
    fun build(
        state: EvidenceGraphState,
        now: Long
    ): EvidenceInspectorSnapshot {
        require(now > 0)

        val entries = state.claims
            .map { claim ->
                claimEntry(
                    state = state,
                    claim = claim,
                    now = now
                )
            }
            .sortedWith(
                compareBy<EvidenceInspectorClaim> {
                    stateRank(it.effectiveState)
                }.thenByDescending {
                    it.projectRelevancePercent
                }.thenByDescending {
                    maxSourceTime(it)
                }.thenBy {
                    it.claimKey
                }
            )

        return EvidenceInspectorSnapshot(
            totalClaims = state.claims.size,
            totalSources = state.sources.size,
            discovered = state.claims.count {
                it.verificationState ==
                    EvidenceVerificationState.DISCOVERED
            },
            retrieved = state.claims.count {
                it.verificationState ==
                    EvidenceVerificationState.RETRIEVED
            },
            corroborated = state.claims.count {
                it.verificationState ==
                    EvidenceVerificationState.CORROBORATED
            },
            contested = state.claims.count {
                it.verificationState ==
                    EvidenceVerificationState.CONTESTED
            },
            staleEffective = state.claims.count {
                EvidenceGraphReducer.effectiveVerificationState(
                    it,
                    now
                ) == EvidenceVerificationState.STALE
            },
            claims = entries
        )
    }

    private fun claimEntry(
        state: EvidenceGraphState,
        claim: EvidenceClaimNode,
        now: Long
    ): EvidenceInspectorClaim {
        val effective =
            EvidenceGraphReducer.effectiveVerificationState(
                claim,
                now
            )

        val support = sources(
            state,
            claim.supportSourceIds
        )
        val contradictions = sources(
            state,
            claim.contradictionSourceIds
        )
        val mentions = sources(
            state,
            claim.mentionSourceIds
        )

        return EvidenceInspectorClaim(
            id = claim.id,
            claimKey = claim.claimKey,
            statement = claim.statement,
            storedState = claim.verificationState.name,
            effectiveState = effective.name,
            supportSources = support,
            contradictionSources = contradictions,
            mentionSources = mentions,
            evidenceCount = claim.evidenceIds.distinct().size,
            projectId = claim.projectId,
            projectRelevancePercent =
                (claim.projectRelevance * 100.0)
                    .toInt()
                    .coerceIn(0, 100),
            outcome = claim.outcome.name,
            outcomeEvidenceCount =
                claim.outcomeEvidenceIds.distinct().size,
            explanation = explanation(
                claim = claim,
                effective = effective,
                support = support,
                contradictions = contradictions
            )
        )
    }

    private fun sources(
        state: EvidenceGraphState,
        ids: List<String>
    ): List<EvidenceInspectorSource> =
        ids
            .distinct()
            .mapNotNull { id ->
                state.sources.firstOrNull {
                    it.id == id
                }
            }
            .sortedByDescending {
                it.lastObservedAt
            }
            .map(::sourceEntry)

    private fun sourceEntry(
        source: EvidenceSourceNode
    ): EvidenceInspectorSource =
        EvidenceInspectorSource(
            id = source.id,
            uri = source.uri,
            host = source.host.orEmpty(),
            kind = source.kind.name,
            retrievalMethod = source.retrievalMethod,
            evidenceCount =
                source.evidenceIds.distinct().size,
            firstObservedAt = source.firstObservedAt,
            lastObservedAt = source.lastObservedAt
        )

    private fun explanation(
        claim: EvidenceClaimNode,
        effective: EvidenceVerificationState,
        support: List<EvidenceInspectorSource>,
        contradictions: List<EvidenceInspectorSource>
    ): String = when {
        effective == EvidenceVerificationState.STALE ->
            "Evidence is older than the active freshness window. Historical evidence remains stored, but current reuse should be revalidated."

        claim.verificationState ==
            EvidenceVerificationState.CONTESTED ->
            "Verified source evidence contains both support and contradiction. Lumena must preserve the conflict instead of choosing a side from memory."

        claim.verificationState ==
            EvidenceVerificationState.CORROBORATED ->
            "Supported by at least two independent source hosts. Corroboration strengthens source evidence but is still not execution permission or proof of whole-task completion."

        claim.verificationState ==
            EvidenceVerificationState.RETRIEVED ->
            "At least one source was actually retrieved through a verified tool result. A single source is not independent corroboration."

        claim.verificationState ==
            EvidenceVerificationState.DISCOVERED ->
            "Discovered from search-level evidence only. The source has not yet been promoted to retrieved/corroborated evidence."

        support.isEmpty() &&
            contradictions.isNotEmpty() ->
            "Only contradicting source evidence is present; inspect the source records before relying on the claim."

        claim.outcome ==
            EvidenceProjectOutcome.VERIFIED_BY_TEST ->
            "Project outcome is linked to typed PROJECT_TEST proof."

        else ->
            "Typed evidence record. It is advisory context and cannot grant ToolRegistry/ToolGate authority."
    }

    private fun stateRank(
        state: String
    ): Int = when (state) {
        EvidenceVerificationState.CONTESTED.name -> 0
        EvidenceVerificationState.STALE.name -> 1
        EvidenceVerificationState.CORROBORATED.name -> 2
        EvidenceVerificationState.RETRIEVED.name -> 3
        EvidenceVerificationState.DISCOVERED.name -> 4
        else -> 5
    }

    private fun maxSourceTime(
        claim: EvidenceInspectorClaim
    ): Long =
        (
            claim.supportSources +
                claim.contradictionSources +
                claim.mentionSources
            )
            .maxOfOrNull {
                it.lastObservedAt
            }
            ?: 0L
}
