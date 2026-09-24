package com.lumena.android.settings

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
}
