package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceSemanticLinkPolicyTest {
    private val t0 = 1_800_000_000_000L

    private data class Fixture(
        val state: EvidenceGraphState,
        val sourceId: String
    )

    private fun source(
        uri: String,
        statement: String,
        evidenceId: String,
        at: Long,
        kind: EvidenceSourceKind =
            EvidenceSourceKind.WEB_PAGE,
        method: String = "web.read"
    ): Fixture {
        val update = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = "source:$uri",
                statement = statement,
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = uri,
                sourceKind = kind,
                retrievalMethod = method,
                evidenceId = evidenceId,
                observedAt = at,
                projectId = "lumena",
                projectRelevance = 0.8
            )
        )
        assertTrue(update.accepted)
        return Fixture(
            state = update.state,
            sourceId = update.sourceId!!
        )
    }

    @Test
    fun hallucinatedSourceIdIsRejectedWithoutMutation() {
        val fixture = source(
            uri = "https://docs.example/a",
            statement =
                "Vulkan backend requires the documented Android extension.",
            evidenceId = "e1",
            at = t0
        )

        val result = EvidenceSemanticLinkPolicy.propose(
            fixture.state,
            EvidenceSemanticLinkProposal(
                claimKey = "vulkan-extension",
                statement =
                    "Android Vulkan requires the documented extension.",
                relation = EvidenceRelation.SUPPORTS,
                sourceId = "hallucinated-source",
                quotedFragment =
                    "requires the documented Android extension",
                extractorModelId = "gemma4:31b-cloud",
                at = t0 + 1,
                projectId = "lumena"
            )
        )

        assertFalse(result.accepted)
        assertEquals(
            "SOURCE_NOT_FOUND",
            result.reason
        )
        assertEquals(fixture.state, result.state)
    }

    @Test
    fun modelCannotGroundAQuoteThatIsNotInVerifiedSourceExcerpt() {
        val fixture = source(
            uri = "https://docs.example/a",
            statement =
                "The source discusses Android Vulkan initialization.",
            evidenceId = "e1",
            at = t0
        )

        val result = EvidenceSemanticLinkPolicy.propose(
            fixture.state,
            EvidenceSemanticLinkProposal(
                claimKey = "invented-performance-claim",
                statement =
                    "The source says Vulkan is always twice as fast.",
                relation = EvidenceRelation.SUPPORTS,
                sourceId = fixture.sourceId,
                quotedFragment =
                    "Vulkan is always twice as fast",
                extractorModelId = "gemma4:31b-cloud",
                at = t0 + 1
            )
        )

        assertFalse(result.accepted)
        assertEquals(
            "QUOTE_NOT_GROUNDED",
            result.reason
        )
        assertTrue(result.state.semanticLinks.isEmpty())
    }

    @Test
    fun groundedModelProposalCreatesAdvisorySemanticLinkOnly() {
        val fixture = source(
            uri = "https://docs.example/a",
            statement =
                "Android Vulkan initialization uses a VkInstance before device selection.",
            evidenceId = "e1",
            at = t0
        )

        val result = EvidenceSemanticLinkPolicy.propose(
            fixture.state,
            EvidenceSemanticLinkProposal(
                claimKey = "android-vulkan-instance",
                statement =
                    "Android Vulkan initialization creates a VkInstance before choosing a device.",
                relation = EvidenceRelation.SUPPORTS,
                sourceId = fixture.sourceId,
                quotedFragment =
                    "uses a VkInstance before device selection",
                extractorModelId = "gemma4:31b-cloud",
                at = t0 + 1,
                projectId = "lumena"
            )
        )

        assertTrue(result.accepted)
        val semanticClaim =
            result.state.claims.first {
                it.id == result.claimId
            }
        assertEquals(
            EvidenceVerificationState.DISCOVERED,
            semanticClaim.verificationState
        )
        assertTrue(
            semanticClaim.supportSourceIds.isEmpty()
        )
        assertTrue(
            semanticClaim.contradictionSourceIds.isEmpty()
        )
        assertTrue(
            semanticClaim.mentionSourceIds.isEmpty()
        )
        assertTrue(
            semanticClaim.evidenceIds.isEmpty()
        )

        val link =
            result.state.semanticLinks.single()
        assertEquals(
            EvidenceSemanticLinkStatus
                .MODEL_GROUNDED_PROPOSAL,
            link.status
        )
        assertEquals(
            fixture.sourceId,
            link.sourceId
        )
        assertEquals(
            listOf("e1"),
            link.sourceEvidenceIds
        )
        assertEquals(
            "gemma4:31b-cloud",
            link.extractorModelId
        )
    }

    @Test
    fun twoIndependentModelGroundedSourcesStillDoNotSelfPromoteClaim() {
        val first = source(
            uri = "https://one.example/vulkan",
            statement =
                "The API requires feature X for this Android path.",
            evidenceId = "e1",
            at = t0
        )
        val second = source(
            uri = "https://two.example/vulkan",
            statement =
                "Independent documentation also requires feature X here.",
            evidenceId = "e2",
            at = t0 + 1
        )

        var state = first.state.copy(
            sources =
                first.state.sources +
                    second.state.sources,
            claims =
                first.state.claims +
                    second.state.claims
        )

        val p1 = EvidenceSemanticLinkPolicy.propose(
            state,
            EvidenceSemanticLinkProposal(
                claimKey = "feature-x-required",
                statement =
                    "Feature X is required for the Android path.",
                relation = EvidenceRelation.SUPPORTS,
                sourceId = first.sourceId,
                quotedFragment =
                    "requires feature X for this Android path",
                extractorModelId = "model-a",
                at = t0 + 2
            )
        )
        assertTrue(p1.accepted)
        state = p1.state

        val p2 = EvidenceSemanticLinkPolicy.propose(
            state,
            EvidenceSemanticLinkProposal(
                claimKey = "feature-x-required",
                statement =
                    "Feature X is required for the Android path.",
                relation = EvidenceRelation.SUPPORTS,
                sourceId = second.sourceId,
                quotedFragment =
                    "also requires feature X here",
                extractorModelId = "model-b",
                at = t0 + 3
            )
        )
        assertTrue(p2.accepted)
        state = p2.state

        val claim =
            state.claims.first {
                it.id == p2.claimId
            }
        assertEquals(
            EvidenceVerificationState.DISCOVERED,
            claim.verificationState
        )
        assertTrue(claim.supportSourceIds.isEmpty())
        assertEquals(
            2,
            EvidenceSemanticLinkPolicy
                .linksForClaim(state, claim.id)
                .size
        )
        assertNotEquals(
            EvidenceVerificationState.CORROBORATED,
            claim.verificationState
        )
    }

    @Test
    fun searchSnippetCanGroundProposalButCannotUpgradeSemanticVerification() {
        val fixture = source(
            uri = "https://search.example/item",
            statement =
                "Search title — Search snippet mentions Python 3.15 release candidate.",
            evidenceId = "search-1",
            at = t0,
            kind = EvidenceSourceKind.SEARCH_SNIPPET,
            method = "web.search"
        )

        val result = EvidenceSemanticLinkPolicy.propose(
            fixture.state,
            EvidenceSemanticLinkProposal(
                claimKey = "python-315-rc",
                statement =
                    "A source mentions a Python 3.15 release candidate.",
                relation = EvidenceRelation.MENTIONS,
                sourceId = fixture.sourceId,
                quotedFragment =
                    "mentions Python 3.15 release candidate",
                extractorModelId = "model-a",
                at = t0 + 1
            )
        )

        assertTrue(result.accepted)
        val claim =
            result.state.claims.first {
                it.id == result.claimId
            }
        assertEquals(
            EvidenceVerificationState.DISCOVERED,
            claim.verificationState
        )
        assertTrue(claim.mentionSourceIds.isEmpty())
    }

    @Test
    fun duplicateProposalIsIdempotent() {
        val fixture = source(
            uri = "https://docs.example/repeat",
            statement =
                "The verified source says the same bounded fact.",
            evidenceId = "e1",
            at = t0
        )

        val proposal = EvidenceSemanticLinkProposal(
            claimKey = "bounded-fact",
            statement = "The bounded fact is present.",
            relation = EvidenceRelation.SUPPORTS,
            sourceId = fixture.sourceId,
            quotedFragment =
                "says the same bounded fact",
            extractorModelId = "model-a",
            at = t0 + 1
        )

        val first =
            EvidenceSemanticLinkPolicy.propose(
                fixture.state,
                proposal
            )
        val second =
            EvidenceSemanticLinkPolicy.propose(
                first.state,
                proposal.copy(at = t0 + 2)
            )

        assertTrue(first.accepted)
        assertTrue(second.accepted)
        assertEquals(
            1,
            second.state.semanticLinks.size
        )
        assertEquals(
            first.state.semanticLinks.single().id,
            second.state.semanticLinks.single().id
        )
    }
}
