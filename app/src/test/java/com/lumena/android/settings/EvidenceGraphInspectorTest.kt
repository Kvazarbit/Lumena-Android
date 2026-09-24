package com.lumena.android.settings

import com.lumena.android.agent.core.EvidenceApplicationBinding
import com.lumena.android.agent.core.EvidenceApplicationStatus
import com.lumena.android.agent.core.EvidenceClaimCandidate
import com.lumena.android.agent.core.EvidenceClaimCandidateProvenance
import com.lumena.android.agent.core.EvidenceClaimCandidateStatus
import com.lumena.android.agent.core.EvidenceGraphReducer
import com.lumena.android.agent.core.EvidenceGraphState
import com.lumena.android.agent.core.EvidenceObservation
import com.lumena.android.agent.core.EvidenceRelation
import com.lumena.android.agent.core.EvidenceSourceKind
import com.lumena.android.agent.core.EvidenceVerificationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphInspectorTest {
    private val t0 = 1_800_000_000_000L

    private fun observation(
        claimKey: String,
        relation: EvidenceRelation,
        uri: String,
        kind: EvidenceSourceKind,
        method: String,
        evidenceId: String,
        at: Long,
        statement: String = "Verified technical statement",
        projectRelevance: Double = 0.8
    ) = EvidenceObservation(
        claimKey = claimKey,
        statement = statement,
        relation = relation,
        sourceUri = uri,
        sourceKind = kind,
        retrievalMethod = method,
        evidenceId = evidenceId,
        observedAt = at,
        projectId = "lumena",
        projectRelevance = projectRelevance
    )

    @Test
    fun inspectorShowsDiscoveredRetrievedCorroboratedAndContestedEvidence() {
        var state = EvidenceGraphState()

        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "discovered",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://search.example/a",
                kind = EvidenceSourceKind.SEARCH_SNIPPET,
                method = "web.search",
                evidenceId = "d1",
                at = t0
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "retrieved",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://docs.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "r1",
                at = t0 + 1
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "corroborated",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://one.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "c1",
                at = t0 + 2
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "corroborated",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://two.example/a",
                kind = EvidenceSourceKind.PUBLIC_API,
                method = "http.json",
                evidenceId = "c2",
                at = t0 + 3
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "contested",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://support.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "x1",
                at = t0 + 4
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            observation(
                claimKey = "contested",
                relation = EvidenceRelation.CONTRADICTS,
                uri = "https://contradict.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "x2",
                at = t0 + 5
            )
        ).state

        val snapshot = EvidenceGraphInspectorPolicy.build(
            state = state,
            now = t0 + 6
        )

        assertEquals(4, snapshot.totalClaims)
        assertEquals(6, snapshot.totalSources)
        assertEquals(1, snapshot.discovered)
        assertEquals(1, snapshot.retrieved)
        assertEquals(1, snapshot.corroborated)
        assertEquals(1, snapshot.contested)

        val contested = snapshot.claims.first {
            it.claimKey == "contested"
        }
        assertEquals(
            EvidenceVerificationState.CONTESTED.name,
            contested.effectiveState
        )
        assertEquals(1, contested.supportSources.size)
        assertEquals(1, contested.contradictionSources.size)
        assertTrue(
            contested.explanation.contains(
                "preserve the conflict"
            )
        )

        val corroborated = snapshot.claims.first {
            it.claimKey == "corroborated"
        }
        assertEquals(2, corroborated.supportSources.size)
        assertTrue(
            corroborated.explanation.contains(
                "two independent source hosts"
            )
        )
        assertTrue(
            corroborated.explanation.contains(
                "not execution permission"
            )
        )
    }

    @Test
    fun inspectorShowsEffectiveStalenessWithoutChangingStoredState() {
        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            observation(
                claimKey = "aging",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://docs.example/aging",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "old-1",
                at = t0
            )
        ).state

        val now =
            t0 +
                EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS +
                1

        val snapshot = EvidenceGraphInspectorPolicy.build(
            state = state,
            now = now
        )
        val claim = snapshot.claims.single()

        assertEquals(
            EvidenceVerificationState.RETRIEVED.name,
            claim.storedState
        )
        assertEquals(
            EvidenceVerificationState.STALE.name,
            claim.effectiveState
        )
        assertEquals(1, snapshot.staleEffective)
        assertTrue(
            claim.explanation.contains(
                "Historical evidence remains stored"
            )
        )

        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            state.claims.single().verificationState
        )
    }

    @Test
    fun inspectorCarriesRetrievalMethodEvidenceCountAndProjectRelevance() {
        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            observation(
                claimKey = "api-status",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://api.example/status",
                kind = EvidenceSourceKind.PUBLIC_API,
                method = "http.json",
                evidenceId = "api-1",
                at = t0,
                projectRelevance = 0.87
            )
        ).state

        val snapshot = EvidenceGraphInspectorPolicy.build(
            state = state,
            now = t0 + 1
        )
        val claim = snapshot.claims.single()
        val source = claim.supportSources.single()

        assertEquals("PUBLIC_API", source.kind)
        assertEquals("http.json", source.retrievalMethod)
        assertEquals(1, source.evidenceCount)
        assertEquals(87, claim.projectRelevancePercent)
        assertEquals("lumena", claim.projectId)
        assertEquals(1, claim.evidenceCount)
    }

    @Test
    fun inspectorSeparatesSemanticCandidatesFromVerifiedClaims() {
        val sourceState = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            observation(
                claimKey = "source:https://docs.example/workmanager",
                relation = EvidenceRelation.SUPPORTS,
                uri = "https://docs.example/workmanager",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "source-1",
                at = t0,
                statement =
                    "Android WorkManager supports persistent background work."
            )
        ).state
        val sourceId = sourceState.sources.single().id
        val pending = EvidenceClaimCandidate(
            id = "candidate-pending",
            claimKey = "workmanager-persistent",
            statement =
                "Android WorkManager supports persistent background work.",
            sourceIds = listOf(sourceId),
            provenance =
                EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
            status = EvidenceClaimCandidateStatus.PENDING,
            lexicalCoverage = 0.75,
            proposedAt = t0 + 1,
            projectId = "lumena",
            projectRelevance = 0.9
        )
        val promoted = pending.copy(
            id = "candidate-promoted",
            status = EvidenceClaimCandidateStatus.PROMOTED,
            proposedAt = t0 + 2,
            resolutionEvidenceIds = listOf("claim-proof")
        )
        val rejected = pending.copy(
            id = "candidate-rejected",
            status = EvidenceClaimCandidateStatus.REJECTED,
            proposedAt = t0 + 3,
            resolutionEvidenceIds = listOf("reject-proof")
        )

        val snapshot = EvidenceGraphInspectorPolicy.build(
            state = sourceState.copy(
                candidates = listOf(
                    rejected,
                    promoted,
                    pending
                )
            ),
            now = t0 + 4
        )

        assertEquals(3, snapshot.totalCandidates)
        assertEquals(1, snapshot.pendingCandidates)
        assertEquals(1, snapshot.promotedCandidates)
        assertEquals(1, snapshot.rejectedCandidates)

        val pendingEntry = snapshot.candidates.first {
            it.id == "candidate-pending"
        }
        assertEquals("PENDING", pendingEntry.status)
        assertEquals(75, pendingEntry.lexicalCoveragePercent)
        assertEquals(
            listOf("https://docs.example/workmanager"),
            pendingEntry.sourceUris
        )
        assertTrue(
            pendingEntry.explanation.contains(
                "not verified"
            )
        )

        // Candidate is visible separately; it did not create a semantic claim.
        assertTrue(
            snapshot.claims.none {
                it.claimKey == "workmanager-persistent"
            }
        )
    }

    @Test
    fun inspectorShowsPendingAppliedAndVerifiedProjectBindings() {
        val state = EvidenceGraphState(
            applications = listOf(
                EvidenceApplicationBinding(
                    id = "pending",
                    claimKey = "claim-pending",
                    projectId = "lumena",
                    target = "app/pending.py",
                    status = EvidenceApplicationStatus.PENDING,
                    createdAt = t0,
                    updatedAt = t0
                ),
                EvidenceApplicationBinding(
                    id = "applied",
                    claimKey = "claim-applied",
                    projectId = "lumena",
                    target = "app/applied.py",
                    status = EvidenceApplicationStatus.APPLIED,
                    createdAt = t0,
                    updatedAt = t0 + 1,
                    artifactEvidenceIds =
                        listOf("write-ok")
                ),
                EvidenceApplicationBinding(
                    id = "verified",
                    claimKey = "claim-verified",
                    projectId = "lumena",
                    target = "app/verified.py",
                    status = EvidenceApplicationStatus.VERIFIED,
                    createdAt = t0,
                    updatedAt = t0 + 2,
                    artifactEvidenceIds =
                        listOf("patch-ok"),
                    testEvidenceIds =
                        listOf("tests-ok")
                )
            )
        )

        val snapshot = EvidenceGraphInspectorPolicy.build(
            state = state,
            now = t0 + 3
        )

        assertEquals(3, snapshot.totalApplications)
        assertEquals(1, snapshot.pendingApplications)
        assertEquals(1, snapshot.appliedApplications)
        assertEquals(1, snapshot.verifiedApplications)
        assertEquals(0, snapshot.rejectedApplications)

        val verified = snapshot.applications.first {
            it.id == "verified"
        }
        assertEquals("VERIFIED", verified.status)
        assertEquals(1, verified.artifactEvidenceCount)
        assertEquals(1, verified.testEvidenceCount)
        assertTrue(
            verified.explanation.contains(
                "verified by a successful local"
            )
        )
    }

    @Test
    fun inspectorBuildIsPureAndDoesNotMutateGraph() {
        val original = EvidenceGraphState(
            candidates = listOf(
                EvidenceClaimCandidate(
                    id = "pure-candidate",
                    claimKey = "pure-claim",
                    statement = "Pure inspector candidate statement.",
                    sourceIds = listOf("missing-source-id"),
                    provenance =
                        EvidenceClaimCandidateProvenance.USER_PROPOSAL,
                    status =
                        EvidenceClaimCandidateStatus.REJECTED,
                    lexicalCoverage = 0.5,
                    proposedAt = t0,
                    resolutionEvidenceIds =
                        listOf("user-decision")
                )
            )
        )

        EvidenceGraphInspectorPolicy.build(
            state = original,
            now = t0 + 1
        )

        assertEquals(
            EvidenceClaimCandidateStatus.REJECTED,
            original.candidates.single().status
        )
        assertEquals(
            listOf("missing-source-id"),
            original.candidates.single().sourceIds
        )
        assertTrue(original.claims.isEmpty())
    }

}
