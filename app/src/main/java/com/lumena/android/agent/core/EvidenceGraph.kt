package com.lumena.android.agent.core

import java.net.URI
import java.security.MessageDigest

enum class EvidenceSourceKind {
    SEARCH_SNIPPET,
    WEB_PAGE,
    PUBLIC_API,
    PROJECT_TEST,
    PROJECT_ARTIFACT
}

enum class EvidenceRelation {
    SUPPORTS,
    CONTRADICTS,
    MENTIONS
}

enum class EvidenceVerificationState {
    DISCOVERED,
    RETRIEVED,
    CORROBORATED,
    CONTESTED,
    STALE
}

enum class EvidenceProjectOutcome {
    UNKNOWN,
    USED_IN_PLAN,
    APPLIED_TO_PROJECT,
    VERIFIED_BY_TEST,
    REJECTED
}

enum class EvidenceOutcomeProofKind {
    USER_DECISION,
    PROJECT_TEST,
    PROJECT_ARTIFACT
}

data class EvidenceObservation(
    val claimKey: String,
    val statement: String,
    val relation: EvidenceRelation,
    val sourceUri: String,
    val sourceKind: EvidenceSourceKind,
    val retrievalMethod: String,
    val evidenceId: String,
    val observedAt: Long,
    val projectId: String? = null,
    val projectRelevance: Double = 0.0,
    val verifiedToolResult: Boolean = true,
    val outcomeUnknown: Boolean = false
) {
    init {
        require(claimKey.isNotBlank())
        require(statement.isNotBlank())
        require(sourceUri.isNotBlank())
        require(retrievalMethod.isNotBlank())
        require(evidenceId.isNotBlank())
        require(observedAt > 0)
        require(projectRelevance in 0.0..1.0)
    }
}

data class EvidenceSourceNode(
    val id: String,
    val uri: String,
    val host: String?,
    val kind: EvidenceSourceKind,
    val retrievalMethod: String,
    val evidenceIds: List<String>,
    val firstObservedAt: Long,
    val lastObservedAt: Long
)

data class EvidenceClaimNode(
    val id: String,
    val claimKey: String,
    val statement: String,
    val verificationState: EvidenceVerificationState,
    val supportSourceIds: List<String> = emptyList(),
    val contradictionSourceIds: List<String> = emptyList(),
    val mentionSourceIds: List<String> = emptyList(),
    val evidenceIds: List<String> = emptyList(),
    val firstObservedAt: Long,
    val lastObservedAt: Long,
    val projectId: String? = null,
    val projectRelevance: Double = 0.0,
    val outcome: EvidenceProjectOutcome = EvidenceProjectOutcome.UNKNOWN,
    val outcomeEvidenceIds: List<String> = emptyList()
)

data class EvidenceGraphState(
    val schemaVersion: Int = 1,
    val claims: List<EvidenceClaimNode> = emptyList(),
    val sources: List<EvidenceSourceNode> = emptyList()
)

data class EvidenceGraphUpdate(
    val state: EvidenceGraphState,
    val accepted: Boolean,
    val reason: String? = null,
    val claimId: String? = null,
    val sourceId: String? = null
)

data class EvidenceOutcomeProof(
    val kind: EvidenceOutcomeProofKind,
    val evidenceId: String,
    val at: Long
) {
    init {
        require(evidenceId.isNotBlank())
        require(at > 0)
    }
}

/**
 * Pure reducer for typed evidence.
 *
 * Only locally verified observations are admitted. Model prose and imported
 * text are not valid inputs because this API requires a verified evidence
 * observation. The reducer never executes tools or grants authority.
 */
object EvidenceGraphReducer {
    const val DEFAULT_STALE_AFTER_MS: Long = 30L * 24L * 60L * 60L * 1_000L

    fun record(
        state: EvidenceGraphState,
        observation: EvidenceObservation
    ): EvidenceGraphUpdate {
        if (!observation.verifiedToolResult) {
            return EvidenceGraphUpdate(
                state = state,
                accepted = false,
                reason = "UNVERIFIED_EVIDENCE"
            )
        }
        if (observation.outcomeUnknown) {
            return EvidenceGraphUpdate(
                state = state,
                accepted = false,
                reason = "UNKNOWN_EFFECT"
            )
        }

        val sourceId = sourceId(observation)
        val claimId = claimId(observation.claimKey)

        val existingSource = state.sources.firstOrNull { it.id == sourceId }
        val nextSource = if (existingSource == null) {
            EvidenceSourceNode(
                id = sourceId,
                uri = observation.sourceUri.take(2_000),
                host = hostOf(observation.sourceUri),
                kind = observation.sourceKind,
                retrievalMethod = observation.retrievalMethod.take(120),
                evidenceIds = listOf(observation.evidenceId),
                firstObservedAt = observation.observedAt,
                lastObservedAt = observation.observedAt
            )
        } else {
            existingSource.copy(
                evidenceIds = (existingSource.evidenceIds + observation.evidenceId)
                    .distinct()
                    .takeLast(32),
                firstObservedAt = minOf(
                    existingSource.firstObservedAt,
                    observation.observedAt
                ),
                lastObservedAt = maxOf(
                    existingSource.lastObservedAt,
                    observation.observedAt
                )
            )
        }

        val nextSources = (
            state.sources.filterNot { it.id == sourceId } + nextSource
            )
            .sortedBy { it.id }

        val existingClaim = state.claims.firstOrNull { it.id == claimId }
        val baseClaim = existingClaim ?: EvidenceClaimNode(
            id = claimId,
            claimKey = observation.claimKey.trim().take(500),
            statement = observation.statement.trim().take(2_000),
            verificationState = EvidenceVerificationState.DISCOVERED,
            firstObservedAt = observation.observedAt,
            lastObservedAt = observation.observedAt,
            projectId = observation.projectId,
            projectRelevance = observation.projectRelevance
        )

        val support = baseClaim.supportSourceIds.toMutableList()
        val contradict = baseClaim.contradictionSourceIds.toMutableList()
        val mentions = baseClaim.mentionSourceIds.toMutableList()

        when (observation.relation) {
            EvidenceRelation.SUPPORTS -> support += sourceId
            EvidenceRelation.CONTRADICTS -> contradict += sourceId
            EvidenceRelation.MENTIONS -> mentions += sourceId
        }

        val supportDistinct = support.distinct()
        val contradictDistinct = contradict.distinct()
        val mentionDistinct = mentions.distinct()

        val nextVerification = verificationState(
            supportSourceIds = supportDistinct,
            contradictionSourceIds = contradictDistinct,
            mentionSourceIds = mentionDistinct,
            sources = nextSources
        )

        val nextClaim = baseClaim.copy(
            verificationState = nextVerification,
            supportSourceIds = supportDistinct.takeLast(32),
            contradictionSourceIds = contradictDistinct.takeLast(32),
            mentionSourceIds = mentionDistinct.takeLast(32),
            evidenceIds = (baseClaim.evidenceIds + observation.evidenceId)
                .distinct()
                .takeLast(64),
            firstObservedAt = minOf(
                baseClaim.firstObservedAt,
                observation.observedAt
            ),
            lastObservedAt = maxOf(
                baseClaim.lastObservedAt,
                observation.observedAt
            ),
            projectId = observation.projectId ?: baseClaim.projectId,
            projectRelevance = maxOf(
                baseClaim.projectRelevance,
                observation.projectRelevance
            )
        )

        val nextClaims = (
            state.claims.filterNot { it.id == claimId } + nextClaim
            )
            .sortedBy { it.id }

        return EvidenceGraphUpdate(
            state = state.copy(
                claims = nextClaims,
                sources = nextSources
            ),
            accepted = true,
            claimId = claimId,
            sourceId = sourceId
        )
    }

    fun effectiveVerificationState(
        claim: EvidenceClaimNode,
        now: Long,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS
    ): EvidenceVerificationState {
        require(now > 0)
        require(staleAfterMs > 0)

        return if (now - claim.lastObservedAt > staleAfterMs) {
            EvidenceVerificationState.STALE
        } else {
            claim.verificationState
        }
    }

    fun applyProjectOutcome(
        state: EvidenceGraphState,
        claimKey: String,
        outcome: EvidenceProjectOutcome,
        proof: EvidenceOutcomeProof
    ): EvidenceGraphUpdate {
        val id = claimId(claimKey)
        val claim = state.claims.firstOrNull { it.id == id }
            ?: return EvidenceGraphUpdate(
                state = state,
                accepted = false,
                reason = "CLAIM_NOT_FOUND"
            )

        val allowed = when (outcome) {
            EvidenceProjectOutcome.UNKNOWN -> false
            EvidenceProjectOutcome.USED_IN_PLAN -> true
            EvidenceProjectOutcome.APPLIED_TO_PROJECT ->
                proof.kind == EvidenceOutcomeProofKind.PROJECT_ARTIFACT
            EvidenceProjectOutcome.VERIFIED_BY_TEST ->
                proof.kind == EvidenceOutcomeProofKind.PROJECT_TEST
            EvidenceProjectOutcome.REJECTED ->
                proof.kind in setOf(
                    EvidenceOutcomeProofKind.USER_DECISION,
                    EvidenceOutcomeProofKind.PROJECT_TEST
                )
        }

        if (!allowed) {
            return EvidenceGraphUpdate(
                state = state,
                accepted = false,
                reason = "INSUFFICIENT_OUTCOME_PROOF",
                claimId = id
            )
        }

        val nextClaim = claim.copy(
            outcome = outcome,
            outcomeEvidenceIds = (
                claim.outcomeEvidenceIds + proof.evidenceId
                )
                .distinct()
                .takeLast(32),
            lastObservedAt = maxOf(claim.lastObservedAt, proof.at)
        )

        return EvidenceGraphUpdate(
            state = state.copy(
                claims = (
                    state.claims.filterNot { it.id == id } + nextClaim
                    ).sortedBy { it.id }
            ),
            accepted = true,
            claimId = id
        )
    }

    fun relevantClaims(
        state: EvidenceGraphState,
        query: String,
        now: Long,
        limit: Int = 8
    ): List<EvidenceClaimNode> {
        val wanted = tokens(query)
        return state.claims
            .map { claim ->
                val searchable = tokens(
                    claim.claimKey + " " + claim.statement
                )
                val overlap = searchable.count { it in wanted }
                val freshness =
                    if (
                        effectiveVerificationState(claim, now) ==
                        EvidenceVerificationState.STALE
                    ) 0 else 4
                val corroboration = when (claim.verificationState) {
                    EvidenceVerificationState.CORROBORATED -> 8
                    EvidenceVerificationState.RETRIEVED -> 5
                    EvidenceVerificationState.CONTESTED -> 3
                    EvidenceVerificationState.DISCOVERED -> 1
                    EvidenceVerificationState.STALE -> 0
                }
                Triple(
                    claim,
                    overlap * 10 +
                        corroboration +
                        freshness +
                        (claim.projectRelevance * 6.0).toInt(),
                    overlap
                )
            }
            .filter { (_, _, overlap) ->
                wanted.isEmpty() || overlap > 0
            }
            .sortedWith(
                compareByDescending<Triple<EvidenceClaimNode, Int, Int>> {
                    it.second
                }.thenByDescending {
                    it.first.lastObservedAt
                }
            )
            .take(limit.coerceIn(1, 16))
            .map { it.first }
    }

    private fun verificationState(
        supportSourceIds: List<String>,
        contradictionSourceIds: List<String>,
        mentionSourceIds: List<String>,
        sources: List<EvidenceSourceNode>
    ): EvidenceVerificationState {
        if (
            supportSourceIds.isNotEmpty() &&
            contradictionSourceIds.isNotEmpty()
        ) {
            return EvidenceVerificationState.CONTESTED
        }

        val supportIndependence = supportSourceIds
            .mapNotNull { id ->
                sources.firstOrNull { it.id == id }
                    ?.let(::independenceKey)
            }
            .distinct()
            .size

        if (supportIndependence >= 2) {
            return EvidenceVerificationState.CORROBORATED
        }

        val retrieved = (
            supportSourceIds +
                contradictionSourceIds +
                mentionSourceIds
            )
            .distinct()
            .mapNotNull { id ->
                sources.firstOrNull { it.id == id }
            }
            .any {
                it.kind != EvidenceSourceKind.SEARCH_SNIPPET
            }

        return if (retrieved) {
            EvidenceVerificationState.RETRIEVED
        } else {
            EvidenceVerificationState.DISCOVERED
        }
    }

    private fun independenceKey(source: EvidenceSourceNode): String =
        source.host?.takeIf { it.isNotBlank() }
            ?: source.id

    private fun sourceId(observation: EvidenceObservation): String =
        sha256(
            observation.sourceKind.name + "|" +
                observation.retrievalMethod.trim().lowercase() + "|" +
                observation.sourceUri.trim()
        ).take(24)

    private fun claimId(claimKey: String): String =
        sha256(
            claimKey
                .trim()
                .lowercase()
                .replace(Regex("\\s+"), " ")
        ).take(24)

    private fun hostOf(uri: String): String? = runCatching {
        URI(uri.trim()).host
            ?.lowercase()
            ?.removePrefix("www.")
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun tokens(value: String): Set<String> =
        value
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}._+-]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
