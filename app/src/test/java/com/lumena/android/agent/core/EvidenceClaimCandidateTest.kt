package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceClaimCandidateTest {
    private val now = 1_800_000_000_000L

    private fun retrieved(
        state: EvidenceGraphState,
        uri: String,
        evidenceId: String,
        statement: String,
        at: Long = now
    ): EvidenceGraphState =
        EvidenceGraphReducer.record(
            state,
            EvidenceObservation(
                claimKey = "source:$uri",
                statement = statement,
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = uri,
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = evidenceId,
                observedAt = at,
                projectId = "lumena",
                projectRelevance = 0.9,
                verifiedToolResult = true,
                outcomeUnknown = false
            )
        ).state

    private fun sourceIdFor(
        state: EvidenceGraphState,
        uri: String
    ): String =
        state.sources.first { it.uri == uri }.id

    @Test
    fun groundedModelProposalStaysPendingAndDoesNotBecomeVerifiedClaim() {
        val uri = "https://docs.example/android"
        val state = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(state, uri)

        val update = EvidenceGraphClaimPolicy.propose(
            state,
            EvidenceClaimProposal(
                claimKey = "android-workmanager-persistent",
                statement =
                    "Android WorkManager supports persistent background work.",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1,
                projectId = "lumena",
                projectRelevance = 0.9
            )
        )

        assertTrue(update.accepted)
        val candidate = update.state.candidates.single()
        assertEquals(
            EvidenceClaimCandidateStatus.PENDING,
            candidate.status
        )
        assertTrue(candidate.lexicalCoverage >= 0.35)

        // Proposal must not create a semantic verified claim.
        assertFalse(
            update.state.claims.any {
                it.claimKey == "android-workmanager-persistent"
            }
        )
    }

    @Test
    fun unrelatedModelProposalIsRejectedBySourceGrounding() {
        val uri = "https://docs.example/android"
        val state = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(state, uri)

        val update = EvidenceGraphClaimPolicy.propose(
            state,
            EvidenceClaimProposal(
                claimKey = "unrelated-claim",
                statement =
                    "Bitcoin mining profitability doubled after a market rally.",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1
            )
        )

        assertFalse(update.accepted)
        assertEquals(
            "INSUFFICIENT_SOURCE_GROUNDING",
            update.reason
        )
        assertTrue(update.state.candidates.isEmpty())
    }

    @Test
    fun searchSnippetAloneCannotGroundSemanticModelClaim() {
        val uri = "https://search.example/result"
        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = "source:$uri",
                statement = "Android Vulkan benchmark result",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = uri,
                sourceKind = EvidenceSourceKind.SEARCH_SNIPPET,
                retrievalMethod = "web.search",
                evidenceId = "ev-search",
                observedAt = now
            )
        ).state
        val sourceId = sourceIdFor(state, uri)

        val update = EvidenceGraphClaimPolicy.propose(
            state,
            EvidenceClaimProposal(
                claimKey = "android-vulkan-performance",
                statement = "Android Vulkan benchmark performance result",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1
            )
        )

        assertFalse(update.accepted)
        assertEquals("SEARCH_ONLY_SOURCE", update.reason)
    }

    @Test
    fun unverifiedOrMentionObservationCannotPromoteCandidate() {
        val uri = "https://docs.example/android"
        val seeded = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(seeded, uri)
        val proposed = EvidenceGraphClaimPolicy.propose(
            seeded,
            EvidenceClaimProposal(
                claimKey = "android-workmanager-persistent",
                statement =
                    "Android WorkManager supports persistent background work.",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1
            )
        )
        val candidateId = proposed.candidateId!!

        val unverified = EvidenceGraphClaimPolicy
            .resolveWithVerifiedObservation(
                proposed.state,
                candidateId,
                EvidenceObservation(
                    claimKey = "android-workmanager-persistent",
                    statement =
                        "Android WorkManager supports persistent background work.",
                    relation = EvidenceRelation.SUPPORTS,
                    sourceUri = uri,
                    sourceKind = EvidenceSourceKind.WEB_PAGE,
                    retrievalMethod = "web.read",
                    evidenceId = "ev-unverified",
                    observedAt = now + 2,
                    verifiedToolResult = false
                )
            )

        assertFalse(unverified.accepted)
        assertEquals("UNVERIFIED_EVIDENCE", unverified.reason)

        val mention = EvidenceGraphClaimPolicy
            .resolveWithVerifiedObservation(
                proposed.state,
                candidateId,
                EvidenceObservation(
                    claimKey = "android-workmanager-persistent",
                    statement =
                        "Android WorkManager supports persistent background work.",
                    relation = EvidenceRelation.MENTIONS,
                    sourceUri = uri,
                    sourceKind = EvidenceSourceKind.WEB_PAGE,
                    retrievalMethod = "web.read",
                    evidenceId = "ev-mention",
                    observedAt = now + 3
                )
            )

        assertFalse(mention.accepted)
        assertEquals("MENTION_CANNOT_PROMOTE", mention.reason)
    }

    @Test
    fun differentSourceCannotPromoteBoundCandidate() {
        val firstUri = "https://docs.example/android"
        val secondUri = "https://other.example/android"
        var state = retrieved(
            EvidenceGraphState(),
            firstUri,
            "ev-first",
            "Android WorkManager supports persistent background work requests."
        )
        state = retrieved(
            state,
            secondUri,
            "ev-second",
            "Android WorkManager supports persistent background work requests.",
            now + 1
        )

        val firstId = sourceIdFor(state, firstUri)
        val proposed = EvidenceGraphClaimPolicy.propose(
            state,
            EvidenceClaimProposal(
                claimKey = "android-workmanager-persistent",
                statement =
                    "Android WorkManager supports persistent background work.",
                sourceIds = listOf(firstId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 2
            )
        )

        val resolution = EvidenceGraphClaimPolicy
            .resolveWithVerifiedObservation(
                proposed.state,
                proposed.candidateId!!,
                EvidenceObservation(
                    claimKey = "android-workmanager-persistent",
                    statement =
                        "Android WorkManager supports persistent background work.",
                    relation = EvidenceRelation.SUPPORTS,
                    sourceUri = secondUri,
                    sourceKind = EvidenceSourceKind.WEB_PAGE,
                    retrievalMethod = "web.read",
                    evidenceId = "ev-wrong-source",
                    observedAt = now + 3
                )
            )

        assertFalse(resolution.accepted)
        assertEquals(
            "SOURCE_NOT_BOUND_TO_CANDIDATE",
            resolution.reason
        )
    }

    @Test
    fun verifiedBoundObservationPromotesCandidateAndCreatesRetrievedClaim() {
        val uri = "https://docs.example/android"
        val seeded = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(seeded, uri)
        val proposed = EvidenceGraphClaimPolicy.propose(
            seeded,
            EvidenceClaimProposal(
                claimKey = "android-workmanager-persistent",
                statement =
                    "Android WorkManager supports persistent background work.",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1
            )
        )

        val promoted = EvidenceGraphClaimPolicy
            .resolveWithVerifiedObservation(
                proposed.state,
                proposed.candidateId!!,
                EvidenceObservation(
                    claimKey = "android-workmanager-persistent",
                    statement =
                        "Android WorkManager supports persistent background work.",
                    relation = EvidenceRelation.SUPPORTS,
                    sourceUri = uri,
                    sourceKind = EvidenceSourceKind.WEB_PAGE,
                    retrievalMethod = "web.read",
                    evidenceId = "ev-promote",
                    observedAt = now + 2
                )
            )

        assertTrue(promoted.accepted)
        assertEquals(
            EvidenceClaimCandidateStatus.PROMOTED,
            promoted.state.candidates.single().status
        )

        val semanticClaim = promoted.state.claims.first {
            it.claimKey == "android-workmanager-persistent"
        }
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            semanticClaim.verificationState
        )
        assertEquals(
            listOf("ev-promote"),
            promoted.state.candidates.single()
                .resolutionEvidenceIds
        )
    }

    @Test
    fun twoIndependentlyVerifiedCandidatesCanCorroborateSameSemanticClaim() {
        val firstUri = "https://docs.example/android"
        val secondUri = "https://independent.example/workmanager"
        var state = retrieved(
            EvidenceGraphState(),
            firstUri,
            "ev-first-source",
            "Android WorkManager supports persistent background work requests."
        )
        state = retrieved(
            state,
            secondUri,
            "ev-second-source",
            "Android WorkManager supports persistent background work requests.",
            now + 1
        )

        val claimKey = "android-workmanager-persistent"
        val statement =
            "Android WorkManager supports persistent background work."

        fun proposeAndPromote(
            current: EvidenceGraphState,
            uri: String,
            evidenceId: String,
            at: Long
        ): EvidenceGraphState {
            val sourceId = sourceIdFor(current, uri)
            val proposal = EvidenceGraphClaimPolicy.propose(
                current,
                EvidenceClaimProposal(
                    claimKey = claimKey,
                    statement = statement,
                    sourceIds = listOf(sourceId),
                    provenance =
                        EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                    proposedAt = at
                )
            )
            assertTrue(proposal.accepted)
            val promoted = EvidenceGraphClaimPolicy
                .resolveWithVerifiedObservation(
                    proposal.state,
                    proposal.candidateId!!,
                    EvidenceObservation(
                        claimKey = claimKey,
                        statement = statement,
                        relation = EvidenceRelation.SUPPORTS,
                        sourceUri = uri,
                        sourceKind = EvidenceSourceKind.WEB_PAGE,
                        retrievalMethod = "web.read",
                        evidenceId = evidenceId,
                        observedAt = at + 1
                    )
                )
            assertTrue(promoted.accepted)
            return promoted.state
        }

        state = proposeAndPromote(
            state,
            firstUri,
            "ev-first-claim",
            now + 2
        )
        state = proposeAndPromote(
            state,
            secondUri,
            "ev-second-claim",
            now + 4
        )

        val semanticClaim = state.claims.first {
            it.claimKey == claimKey
        }
        assertEquals(
            EvidenceVerificationState.CORROBORATED,
            semanticClaim.verificationState
        )
        assertEquals(2, semanticClaim.supportSourceIds.size)
    }

    @Test
    fun duplicateProposalIsIdempotent() {
        val uri = "https://docs.example/android"
        val seeded = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(seeded, uri)
        val proposal = EvidenceClaimProposal(
            claimKey = "android-workmanager-persistent",
            statement =
                "Android WorkManager supports persistent background work.",
            sourceIds = listOf(sourceId),
            provenance =
                EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
            proposedAt = now + 1
        )

        val first = EvidenceGraphClaimPolicy.propose(
            seeded,
            proposal
        )
        val second = EvidenceGraphClaimPolicy.propose(
            first.state,
            proposal.copy(proposedAt = now + 100)
        )

        assertTrue(first.accepted)
        assertTrue(second.accepted)
        assertEquals(first.candidateId, second.candidateId)
        assertEquals(1, second.state.candidates.size)
    }

    @Test
    fun rejectedCandidateNeedsEvidenceAndNeverChangesVerifiedClaims() {
        val uri = "https://docs.example/android"
        val seeded = retrieved(
            EvidenceGraphState(),
            uri,
            "ev-source",
            "Android WorkManager supports persistent background work requests."
        )
        val sourceId = sourceIdFor(seeded, uri)
        val proposed = EvidenceGraphClaimPolicy.propose(
            seeded,
            EvidenceClaimProposal(
                claimKey = "android-workmanager-persistent",
                statement =
                    "Android WorkManager supports persistent background work.",
                sourceIds = listOf(sourceId),
                provenance =
                    EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                proposedAt = now + 1
            )
        )

        val noEvidence = EvidenceGraphClaimPolicy.reject(
            proposed.state,
            proposed.candidateId!!,
            evidenceId = ""
        )
        assertFalse(noEvidence.accepted)
        assertEquals(
            "MISSING_REJECTION_EVIDENCE",
            noEvidence.reason
        )

        val beforeClaims = proposed.state.claims
        val rejected = EvidenceGraphClaimPolicy.reject(
            proposed.state,
            proposed.candidateId!!,
            evidenceId = "user-rejected-1"
        )
        assertTrue(rejected.accepted)
        assertEquals(beforeClaims, rejected.state.claims)
        assertEquals(
            EvidenceClaimCandidateStatus.REJECTED,
            rejected.state.candidates.single().status
        )
    }
}
