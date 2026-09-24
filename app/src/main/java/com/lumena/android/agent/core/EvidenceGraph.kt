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

enum class EvidenceClaimCandidateProvenance {
    MODEL_PROPOSAL,
    USER_PROPOSAL
}

enum class EvidenceClaimCandidateStatus {
    PENDING,
    PROMOTED,
    REJECTED
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

data class EvidenceClaimCandidate(
    val id: String,
    val claimKey: String,
    val statement: String,
    val sourceIds: List<String>,
    val provenance: EvidenceClaimCandidateProvenance,
    val status: EvidenceClaimCandidateStatus = EvidenceClaimCandidateStatus.PENDING,
    val lexicalCoverage: Double,
    val proposedAt: Long,
    val projectId: String? = null,
    val projectRelevance: Double = 0.0,
    val resolutionEvidenceIds: List<String> = emptyList()
) {
    init {
        require(id.isNotBlank())
        require(claimKey.isNotBlank())
        require(statement.isNotBlank())
        require(sourceIds.isNotEmpty())
        require(lexicalCoverage in 0.0..1.0)
        require(proposedAt > 0)
        require(projectRelevance in 0.0..1.0)
    }
}

data class EvidenceGraphState(
    val schemaVersion: Int = 1,
    val claims: List<EvidenceClaimNode> = emptyList(),
    val sources: List<EvidenceSourceNode> = emptyList(),
    val candidates: List<EvidenceClaimCandidate> = emptyList()
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

data class EvidenceClaimProposal(
    val claimKey: String,
    val statement: String,
    val sourceIds: List<String>,
    val provenance: EvidenceClaimCandidateProvenance,
    val proposedAt: Long,
    val projectId: String? = null,
    val projectRelevance: Double = 0.0
) {
    init {
        require(claimKey.isNotBlank())
        require(statement.isNotBlank())
        require(sourceIds.isNotEmpty())
        require(proposedAt > 0)
        require(projectRelevance in 0.0..1.0)
    }
}

data class EvidenceClaimCandidateUpdate(
    val state: EvidenceGraphState,
    val accepted: Boolean,
    val reason: String? = null,
    val candidateId: String? = null
)


/**
 * Pure reducer for typed evidence.
 *
 * Only locally verified observations are admitted. Model prose and imported
 * text are not valid inputs because this API requires a verified evidence
 * observation. The reducer never executes tools or grants authority.
 */
object EvidenceGraphReducer {
    const val DEFAULT_STALE_AFTER_MS: Long = 30L * 24L * 60L * 60L * 1_000L

    internal fun sourceIdForObservation(
        observation: EvidenceObservation
    ): String = sourceId(observation)

    internal fun claimIdForKey(
        claimKey: String
    ): String = claimId(claimKey)

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

        val nextStatement =
            if (
                baseClaim.verificationState ==
                    EvidenceVerificationState.DISCOVERED &&
                observation.sourceKind !=
                    EvidenceSourceKind.SEARCH_SNIPPET
            ) {
                observation.statement.trim().take(2_000)
            } else {
                baseClaim.statement
            }

        val nextClaim = baseClaim.copy(
            statement = nextStatement,
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

/**
 * Conservative semantic-claim candidate layer.
 *
 * Model/user proposals never enter verified claims directly. A proposal must
 * reference at least one already retrieved local source and must be lexically
 * grounded in the bounded source text already stored in the graph. Even then it
 * remains PENDING until a new verified EvidenceObservation explicitly supports
 * or contradicts the same claim key from one of the bound sources.
 */
object EvidenceGraphClaimPolicy {
    private const val MIN_MODEL_COVERAGE = 0.35
    private const val MIN_USER_COVERAGE = 0.20
    private const val MAX_CANDIDATE_SOURCES = 8

    fun propose(
        state: EvidenceGraphState,
        proposal: EvidenceClaimProposal
    ): EvidenceClaimCandidateUpdate {
        val sourceIds = proposal.sourceIds
            .distinct()
            .take(MAX_CANDIDATE_SOURCES)

        val sources = sourceIds.mapNotNull { id ->
            state.sources.firstOrNull { it.id == id }
        }

        if (sources.size != sourceIds.size) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "UNKNOWN_SOURCE"
            )
        }

        if (
            sources.none {
                it.kind != EvidenceSourceKind.SEARCH_SNIPPET
            }
        ) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "SEARCH_ONLY_SOURCE"
            )
        }

        val sourceText = sourceIds
            .flatMap { sourceId ->
                state.claims
                    .filter { claim ->
                        sourceId in claim.supportSourceIds ||
                            sourceId in claim.contradictionSourceIds ||
                            sourceId in claim.mentionSourceIds
                    }
                    .map { claim ->
                        claim.claimKey + " " + claim.statement
                    }
            }
            .joinToString(" ")

        val coverage = lexicalCoverage(
            proposal.statement,
            sourceText
        )

        val threshold = when (proposal.provenance) {
            EvidenceClaimCandidateProvenance.MODEL_PROPOSAL ->
                MIN_MODEL_COVERAGE
            EvidenceClaimCandidateProvenance.USER_PROPOSAL ->
                MIN_USER_COVERAGE
        }

        if (coverage < threshold) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "INSUFFICIENT_SOURCE_GROUNDING"
            )
        }

        val id = candidateId(proposal, sourceIds)
        val existing = state.candidates.firstOrNull {
            it.id == id
        }
        if (existing != null) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = true,
                candidateId = existing.id
            )
        }

        val candidate = EvidenceClaimCandidate(
            id = id,
            claimKey = proposal.claimKey.trim().take(500),
            statement = proposal.statement
                .replace(Regex("[\\r\\n\\t]+"), " ")
                .replace(Regex("\\s{2,}"), " ")
                .trim()
                .take(1_600),
            sourceIds = sourceIds,
            provenance = proposal.provenance,
            status = EvidenceClaimCandidateStatus.PENDING,
            lexicalCoverage = coverage,
            proposedAt = proposal.proposedAt,
            projectId = proposal.projectId,
            projectRelevance = proposal.projectRelevance
        )

        return EvidenceClaimCandidateUpdate(
            state = state.copy(
                candidates = (
                    state.candidates + candidate
                    )
                    .distinctBy { it.id }
                    .sortedBy { it.id }
            ),
            accepted = true,
            candidateId = candidate.id
        )
    }

    fun resolveWithVerifiedObservation(
        state: EvidenceGraphState,
        candidateId: String,
        observation: EvidenceObservation
    ): EvidenceClaimCandidateUpdate {
        val candidate = state.candidates.firstOrNull {
            it.id == candidateId
        } ?: return EvidenceClaimCandidateUpdate(
            state = state,
            accepted = false,
            reason = "CANDIDATE_NOT_FOUND"
        )

        if (candidate.status != EvidenceClaimCandidateStatus.PENDING) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "CANDIDATE_ALREADY_RESOLVED",
                candidateId = candidate.id
            )
        }

        if (
            !observation.verifiedToolResult ||
            observation.outcomeUnknown
        ) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "UNVERIFIED_EVIDENCE",
                candidateId = candidate.id
            )
        }

        if (
            observation.claimKey.trim() !=
            candidate.claimKey.trim()
        ) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "CLAIM_KEY_MISMATCH",
                candidateId = candidate.id
            )
        }

        if (observation.relation == EvidenceRelation.MENTIONS) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "MENTION_CANNOT_PROMOTE",
                candidateId = candidate.id
            )
        }

        val sourceId =
            EvidenceGraphReducer.sourceIdForObservation(
                observation
            )
        if (sourceId !in candidate.sourceIds) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "SOURCE_NOT_BOUND_TO_CANDIDATE",
                candidateId = candidate.id
            )
        }

        val reduced = EvidenceGraphReducer.record(
            state = state,
            observation = observation
        )
        if (!reduced.accepted) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = reduced.reason ?: "EVIDENCE_REJECTED",
                candidateId = candidate.id
            )
        }

        val resolvedCandidate = candidate.copy(
            status = EvidenceClaimCandidateStatus.PROMOTED,
            resolutionEvidenceIds = (
                candidate.resolutionEvidenceIds +
                    observation.evidenceId
                )
                .distinct()
                .takeLast(16)
        )

        return EvidenceClaimCandidateUpdate(
            state = reduced.state.copy(
                candidates = (
                    reduced.state.candidates
                        .filterNot { it.id == candidate.id } +
                        resolvedCandidate
                    )
                    .sortedBy { it.id }
            ),
            accepted = true,
            candidateId = candidate.id
        )
    }

    fun reject(
        state: EvidenceGraphState,
        candidateId: String,
        evidenceId: String
    ): EvidenceClaimCandidateUpdate {
        val candidate = state.candidates.firstOrNull {
            it.id == candidateId
        } ?: return EvidenceClaimCandidateUpdate(
            state = state,
            accepted = false,
            reason = "CANDIDATE_NOT_FOUND"
        )

        if (evidenceId.isBlank()) {
            return EvidenceClaimCandidateUpdate(
                state = state,
                accepted = false,
                reason = "MISSING_REJECTION_EVIDENCE",
                candidateId = candidate.id
            )
        }

        val rejected = candidate.copy(
            status = EvidenceClaimCandidateStatus.REJECTED,
            resolutionEvidenceIds = (
                candidate.resolutionEvidenceIds + evidenceId
                )
                .distinct()
                .takeLast(16)
        )
        return EvidenceClaimCandidateUpdate(
            state = state.copy(
                candidates = (
                    state.candidates
                        .filterNot { it.id == candidate.id } +
                        rejected
                    )
                    .sortedBy { it.id }
            ),
            accepted = true,
            candidateId = candidate.id
        )
    }

    private fun lexicalCoverage(
        statement: String,
        sourceText: String
    ): Double {
        val wanted = contentTokens(statement)
        if (wanted.size < 2) return 0.0

        val found = contentTokens(sourceText)
        if (found.isEmpty()) return 0.0

        val overlap = wanted.count { it in found }
        return (
            overlap.toDouble() /
                wanted.size.toDouble()
            )
            .coerceIn(0.0, 1.0)
    }

    private fun contentTokens(
        value: String
    ): Set<String> {
        val stop = setOf(
            "the", "and", "for", "with", "from", "that", "this",
            "про", "для", "та", "або", "цей", "ця", "це", "що",
            "oraz", "dla", "ten", "ta", "to",
            "это", "для", "или", "что"
        )

        return value
            .lowercase()
            .split(Regex("[^\\p{L}\\p{N}._+-]+"))
            .map(String::trim)
            .filter {
                it.length >= 3 &&
                    it !in stop
            }
            .take(48)
            .toSet()
    }

    private fun candidateId(
        proposal: EvidenceClaimProposal,
        sourceIds: List<String>
    ): String =
        sha256(
            proposal.provenance.name + "|" +
                proposal.claimKey.trim().lowercase() + "|" +
                proposal.statement.trim().lowercase() + "|" +
                sourceIds.sorted().joinToString(",")
        ).take(24)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

}
