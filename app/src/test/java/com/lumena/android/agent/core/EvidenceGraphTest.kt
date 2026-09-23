package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphTest {
    private val t0 = 1_800_000_000_000L

    private fun obs(
        key: String = "python-3.15-release",
        statement: String = "Python 3.15 release information",
        relation: EvidenceRelation = EvidenceRelation.SUPPORTS,
        uri: String,
        kind: EvidenceSourceKind,
        method: String,
        evidenceId: String,
        at: Long = t0,
        projectRelevance: Double = 0.0,
        verified: Boolean = true,
        unknown: Boolean = false
    ) = EvidenceObservation(
        claimKey = key,
        statement = statement,
        relation = relation,
        sourceUri = uri,
        sourceKind = kind,
        retrievalMethod = method,
        evidenceId = evidenceId,
        observedAt = at,
        projectId = "lumena",
        projectRelevance = projectRelevance,
        verifiedToolResult = verified,
        outcomeUnknown = unknown
    )

    @Test
    fun unverifiedObservationIsRejectedWithoutMutation() {
        val initial = EvidenceGraphState()
        val update = EvidenceGraphReducer.record(
            initial,
            obs(
                uri = "https://example.org/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "model-prose",
                evidenceId = "m1",
                verified = false
            )
        )

        assertFalse(update.accepted)
        assertEquals("UNVERIFIED_EVIDENCE", update.reason)
        assertEquals(initial, update.state)
    }

    @Test
    fun unknownEffectObservationIsNeverLearned() {
        val initial = EvidenceGraphState()
        val update = EvidenceGraphReducer.record(
            initial,
            obs(
                uri = "https://example.org/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "e1",
                unknown = true
            )
        )

        assertFalse(update.accepted)
        assertEquals("UNKNOWN_EFFECT", update.reason)
        assertTrue(update.state.claims.isEmpty())
        assertTrue(update.state.sources.isEmpty())
    }

    @Test
    fun searchSnippetStartsAsDiscoveredNotVerified() {
        val update = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://python.org/blogs/",
                kind = EvidenceSourceKind.SEARCH_SNIPPET,
                method = "web.search",
                evidenceId = "search-1"
            )
        )

        assertTrue(update.accepted)
        val claim = update.state.claims.single()
        assertEquals(
            EvidenceVerificationState.DISCOVERED,
            claim.verificationState
        )
        assertEquals(1, claim.supportSourceIds.size)
        assertEquals(
            EvidenceSourceKind.SEARCH_SNIPPET,
            update.state.sources.single().kind
        )
    }

    @Test
    fun readingSameSourceUpgradesToRetrievedButNotCorroborated() {
        var state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://python.org/blogs/",
                kind = EvidenceSourceKind.SEARCH_SNIPPET,
                method = "web.search",
                evidenceId = "search-1"
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://python.org/blogs/",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "read-1",
                at = t0 + 1
            )
        ).state

        val claim = state.claims.single()
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            claim.verificationState
        )
        assertEquals(2, claim.supportSourceIds.size)
    }

    @Test
    fun twoIndependentHostsCorroborateClaim() {
        var state = EvidenceGraphState()
        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://python.org/blogs/",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "p1"
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://infoworld.com/python/",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "p2",
                at = t0 + 1
            )
        ).state

        assertEquals(
            EvidenceVerificationState.CORROBORATED,
            state.claims.single().verificationState
        )
    }

    @Test
    fun duplicateRoutesOnSameHostDoNotFakeCorroboration() {
        var state = EvidenceGraphState()
        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://example.org/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "a1"
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://example.org/b",
                kind = EvidenceSourceKind.PUBLIC_API,
                method = "http.json",
                evidenceId = "a2",
                at = t0 + 1
            )
        ).state

        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            state.claims.single().verificationState
        )
    }

    @Test
    fun supportingAndContradictingSourcesMakeClaimContested() {
        var state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://source-a.example/item",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "s1"
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            obs(
                relation = EvidenceRelation.CONTRADICTS,
                uri = "https://source-b.example/item",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "s2",
                at = t0 + 1
            )
        ).state

        val claim = state.claims.single()
        assertEquals(
            EvidenceVerificationState.CONTESTED,
            claim.verificationState
        )
        assertEquals(1, claim.supportSourceIds.size)
        assertEquals(1, claim.contradictionSourceIds.size)
    }

    @Test
    fun stalenessIsComputedWithoutRewritingStoredEvidence() {
        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://example.org/current",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "e1"
            )
        ).state
        val claim = state.claims.single()

        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            claim.verificationState
        )
        assertEquals(
            EvidenceVerificationState.STALE,
            EvidenceGraphReducer.effectiveVerificationState(
                claim,
                now = t0 + EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS + 1
            )
        )
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            claim.verificationState
        )
    }

    @Test
    fun projectRelevanceOnlyMovesUpWithNewVerifiedEvidence() {
        var state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://example.org/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "e1",
                projectRelevance = 0.30
            )
        ).state

        state = EvidenceGraphReducer.record(
            state,
            obs(
                uri = "https://example.net/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "e2",
                projectRelevance = 0.85,
                at = t0 + 1
            )
        ).state

        assertEquals(
            0.85,
            state.claims.single().projectRelevance,
            0.0001
        )
    }

    @Test
    fun verifiedByTestRequiresProjectTestProof() {
        val state = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            obs(
                uri = "https://docs.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "web-1"
            )
        ).state

        val denied = EvidenceGraphReducer.applyProjectOutcome(
            state,
            claimKey = "python-3.15-release",
            outcome = EvidenceProjectOutcome.VERIFIED_BY_TEST,
            proof = EvidenceOutcomeProof(
                kind = EvidenceOutcomeProofKind.PROJECT_ARTIFACT,
                evidenceId = "artifact-1",
                at = t0 + 1
            )
        )
        assertFalse(denied.accepted)
        assertEquals(
            "INSUFFICIENT_OUTCOME_PROOF",
            denied.reason
        )

        val allowed = EvidenceGraphReducer.applyProjectOutcome(
            state,
            claimKey = "python-3.15-release",
            outcome = EvidenceProjectOutcome.VERIFIED_BY_TEST,
            proof = EvidenceOutcomeProof(
                kind = EvidenceOutcomeProofKind.PROJECT_TEST,
                evidenceId = "test-1",
                at = t0 + 2
            )
        )
        assertTrue(allowed.accepted)
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            allowed.state.claims.single().outcome
        )
    }

    @Test
    fun relevantClaimsPreferFreshCorroboratedProjectEvidence() {
        var state = EvidenceGraphState()
        state = EvidenceGraphReducer.record(
            state,
            obs(
                key = "vulkan-android",
                statement = "Vulkan backend details for Android",
                uri = "https://docs1.example/vulkan",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "v1",
                projectRelevance = 0.9
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            obs(
                key = "vulkan-android",
                statement = "Vulkan backend details for Android",
                uri = "https://docs2.example/vulkan",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "v2",
                projectRelevance = 0.9,
                at = t0 + 1
            )
        ).state
        state = EvidenceGraphReducer.record(
            state,
            obs(
                key = "unrelated",
                statement = "Unrelated audio note",
                uri = "https://audio.example/a",
                kind = EvidenceSourceKind.WEB_PAGE,
                method = "web.read",
                evidenceId = "a1",
                projectRelevance = 0.1,
                at = t0 + 2
            )
        ).state

        val relevant = EvidenceGraphReducer.relevantClaims(
            state,
            query = "Android Vulkan backend",
            now = t0 + 3,
            limit = 4
        )

        assertEquals("vulkan-android", relevant.first().claimKey)
        assertEquals(
            EvidenceVerificationState.CORROBORATED,
            relevant.first().verificationState
        )
    }
}
