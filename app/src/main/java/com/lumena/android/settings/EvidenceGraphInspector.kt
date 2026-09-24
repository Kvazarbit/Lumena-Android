package com.lumena.android.settings

import com.lumena.android.agent.core.EvidenceApplicationBinding
import com.lumena.android.agent.core.EvidenceApplicationStatus
import com.lumena.android.agent.core.EvidenceClaimCandidate
import com.lumena.android.agent.core.EvidenceClaimCandidateStatus
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

data class EvidenceInspectorCandidate(
    val id: String,
    val claimKey: String,
    val statement: String,
    val provenance: String,
    val status: String,
    val lexicalCoveragePercent: Int,
    val projectId: String?,
    val projectRelevancePercent: Int,
    val sourceUris: List<String>,
    val proposedAt: Long,
    val resolutionEvidenceCount: Int,
    val explanation: String
)

data class EvidenceInspectorApplication(
    val id: String,
    val claimKey: String,
    val projectId: String,
    val target: String,
    val status: String,
    val createdAt: Long,
    val updatedAt: Long,
    val artifactEvidenceCount: Int,
    val testEvidenceCount: Int,
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
    val totalCandidates: Int,
    val pendingCandidates: Int,
    val promotedCandidates: Int,
    val rejectedCandidates: Int,
    val totalApplications: Int,
    val pendingApplications: Int,
    val appliedApplications: Int,
    val verifiedApplications: Int,
    val rejectedApplications: Int,
    val claims: List<EvidenceInspectorClaim>,
    val candidates: List<EvidenceInspectorCandidate>,
    val applications: List<EvidenceInspectorApplication>
)

/**
 * Read-only projection for explaining the typed Evidence Graph.
 *
 * It never mutates graph state, promotes candidates, binds project work,
 * executes tools, or creates execution authority.
 */
object EvidenceGraphInspectorPolicy {
    fun build(
        state: EvidenceGraphState,
        now: Long
    ): EvidenceInspectorSnapshot {
        require(now > 0)

        val claims = state.claims
            .map { claim ->
                claimEntry(
                    state = state,
                    claim = claim,
                    now = now
                )
            }
            .sortedWith(
                compareBy<EvidenceInspectorClaim> {
                    verificationRank(it.effectiveState)
                }.thenByDescending {
                    it.projectRelevancePercent
                }.thenByDescending {
                    maxSourceTime(it)
                }.thenBy {
                    it.claimKey
                }
            )

        val candidates = state.candidates
            .map { candidate ->
                candidateEntry(
                    state = state,
                    candidate = candidate
                )
            }
            .sortedWith(
                compareBy<EvidenceInspectorCandidate> {
                    candidateRank(it.status)
                }.thenByDescending {
                    it.projectRelevancePercent
                }.thenByDescending {
                    it.proposedAt
                }.thenBy {
                    it.claimKey
                }
            )

        val applications = state.applications
            .map(::applicationEntry)
            .sortedWith(
                compareBy<EvidenceInspectorApplication> {
                    applicationRank(it.status)
                }.thenByDescending {
                    it.updatedAt
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
            totalCandidates = state.candidates.size,
            pendingCandidates = state.candidates.count {
                it.status ==
                    EvidenceClaimCandidateStatus.PENDING
            },
            promotedCandidates = state.candidates.count {
                it.status ==
                    EvidenceClaimCandidateStatus.PROMOTED
            },
            rejectedCandidates = state.candidates.count {
                it.status ==
                    EvidenceClaimCandidateStatus.REJECTED
            },
            totalApplications = state.applications.size,
            pendingApplications = state.applications.count {
                it.status ==
                    EvidenceApplicationStatus.PENDING
            },
            appliedApplications = state.applications.count {
                it.status ==
                    EvidenceApplicationStatus.APPLIED
            },
            verifiedApplications = state.applications.count {
                it.status ==
                    EvidenceApplicationStatus.VERIFIED
            },
            rejectedApplications = state.applications.count {
                it.status ==
                    EvidenceApplicationStatus.REJECTED
            },
            claims = claims,
            candidates = candidates,
            applications = applications
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
                percent(claim.projectRelevance),
            outcome = claim.outcome.name,
            outcomeEvidenceCount =
                claim.outcomeEvidenceIds.distinct().size,
            explanation = claimExplanation(
                claim = claim,
                effective = effective,
                support = support,
                contradictions = contradictions
            )
        )
    }

    private fun candidateEntry(
        state: EvidenceGraphState,
        candidate: EvidenceClaimCandidate
    ): EvidenceInspectorCandidate {
        val sourceUris = candidate.sourceIds
            .distinct()
            .mapNotNull { id ->
                state.sources.firstOrNull {
                    it.id == id
                }?.uri
            }

        return EvidenceInspectorCandidate(
            id = candidate.id,
            claimKey = candidate.claimKey,
            statement = candidate.statement,
            provenance = candidate.provenance.name,
            status = candidate.status.name,
            lexicalCoveragePercent =
                percent(candidate.lexicalCoverage),
            projectId = candidate.projectId,
            projectRelevancePercent =
                percent(candidate.projectRelevance),
            sourceUris = sourceUris,
            proposedAt = candidate.proposedAt,
            resolutionEvidenceCount =
                candidate.resolutionEvidenceIds
                    .distinct()
                    .size,
            explanation = candidateExplanation(candidate)
        )
    }

    private fun applicationEntry(
        binding: EvidenceApplicationBinding
    ): EvidenceInspectorApplication =
        EvidenceInspectorApplication(
            id = binding.id,
            claimKey = binding.claimKey,
            projectId = binding.projectId,
            target = binding.target,
            status = binding.status.name,
            createdAt = binding.createdAt,
            updatedAt = binding.updatedAt,
            artifactEvidenceCount =
                binding.artifactEvidenceIds
                    .distinct()
                    .size,
            testEvidenceCount =
                binding.testEvidenceIds
                    .distinct()
                    .size,
            explanation = applicationExplanation(binding)
        )

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

    private fun claimExplanation(
        claim: EvidenceClaimNode,
        effective: EvidenceVerificationState,
        support: List<EvidenceInspectorSource>,
        contradictions: List<EvidenceInspectorSource>
    ): String = when {
        effective == EvidenceVerificationState.STALE ->
            "Evidence is older than the freshness window. Historical evidence remains stored; current reuse requires revalidation."

        claim.verificationState ==
            EvidenceVerificationState.CONTESTED ->
            "Verified evidence contains both support and contradiction. Lumena must preserve the conflict instead of resolving it by model preference."

        claim.verificationState ==
            EvidenceVerificationState.CORROBORATED ->
            "Supported by at least two independent source hosts. This strengthens evidence, but does not grant permission or prove the whole task complete."

        claim.verificationState ==
            EvidenceVerificationState.RETRIEVED ->
            "At least one source was actually retrieved through a verified tool result. One source is not independent corroboration."

        claim.verificationState ==
            EvidenceVerificationState.DISCOVERED ->
            "Search-level discovery only. The source has not been retrieved yet."

        support.isEmpty() &&
            contradictions.isNotEmpty() ->
            "Only contradicting verified source evidence is present."

        claim.outcome ==
            EvidenceProjectOutcome.VERIFIED_BY_TEST ->
            "Project outcome is linked to typed PROJECT_TEST proof; source freshness remains separate."

        else ->
            "Typed verified evidence. It is context only and cannot grant ToolRegistry/ToolGate authority."
    }

    private fun candidateExplanation(
        candidate: EvidenceClaimCandidate
    ): String = when (candidate.status) {
        EvidenceClaimCandidateStatus.PENDING ->
            "Semantic proposal grounded in known retrieved source text, but not verified. It cannot become evidence until a new verified bound-source observation promotes it."

        EvidenceClaimCandidateStatus.PROMOTED ->
            "Candidate was promoted only after verified bound-source evidence. The proposal itself was never treated as proof."

        EvidenceClaimCandidateStatus.REJECTED ->
            "Candidate was rejected with resolution evidence and remains separate from verified claims."
    }

    private fun applicationExplanation(
        binding: EvidenceApplicationBinding
    ): String = when (binding.status) {
        EvidenceApplicationStatus.PENDING ->
            "Verified claim is bound to a concrete project target, but no successful mutation proof has been observed."

        EvidenceApplicationStatus.APPLIED ->
            "A successful known-outcome artifact mutation matched this project target. This does not yet mean project tests passed."

        EvidenceApplicationStatus.VERIFIED ->
            "Application was first proven by an artifact mutation and later verified by a successful local test/syntax result."

        EvidenceApplicationStatus.REJECTED ->
            "Application binding is rejected and should not be reused as successful project evidence."
    }

    private fun verificationRank(
        state: String
    ): Int = when (state) {
        EvidenceVerificationState.CONTESTED.name -> 0
        EvidenceVerificationState.STALE.name -> 1
        EvidenceVerificationState.CORROBORATED.name -> 2
        EvidenceVerificationState.RETRIEVED.name -> 3
        EvidenceVerificationState.DISCOVERED.name -> 4
        else -> 5
    }

    private fun candidateRank(
        status: String
    ): Int = when (status) {
        EvidenceClaimCandidateStatus.PENDING.name -> 0
        EvidenceClaimCandidateStatus.PROMOTED.name -> 1
        EvidenceClaimCandidateStatus.REJECTED.name -> 2
        else -> 3
    }

    private fun applicationRank(
        status: String
    ): Int = when (status) {
        EvidenceApplicationStatus.PENDING.name -> 0
        EvidenceApplicationStatus.APPLIED.name -> 1
        EvidenceApplicationStatus.VERIFIED.name -> 2
        EvidenceApplicationStatus.REJECTED.name -> 3
        else -> 4
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

    private fun percent(
        value: Double
    ): Int =
        (value.coerceIn(0.0, 1.0) * 100.0)
            .toInt()
            .coerceIn(0, 100)
}
