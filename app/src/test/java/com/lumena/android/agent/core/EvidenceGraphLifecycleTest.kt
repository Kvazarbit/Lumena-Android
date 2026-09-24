package com.lumena.android.agent.core

import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import com.lumena.android.settings.EvidenceGraphCodec
import com.lumena.android.settings.EvidenceGraphInspectorPolicy
import com.lumena.android.settings.EvidenceGraphProjector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceGraphLifecycleTest {
    private val t0 = 1_800_000_000_000L
    private val projectId = "lumena"
    private val url =
        "https://docs.example.org/retry/backoff"
    private val target =
        "scripts/retry_policy.py"
    private val semanticClaimKey =
        "retry-backoff-is-bounded"
    private val semanticStatement =
        "Retry backoff uses bounded exponential delays."

    private fun task() = TaskState(
        id = "evidence-lifecycle",
        projectId = projectId,
        goal =
            "Find current retry backoff documentation and apply it to the project",
        status = TaskStatus.WAITING_MODEL
    )

    @Test
    fun verifiedWebEvidenceCanReachTestVerifiedProjectOutcomeEndToEnd() {
        var state = EvidenceGraphState()

        // 1. Search discovers a source, but discovery alone is not retrieval.
        val searchObservations =
            EvidenceGraphProjector.fromToolResult(
                task = task(),
                request = ToolRequest(
                    tool = "web.search",
                    args = mapOf(
                        "query" to
                            "retry bounded exponential backoff documentation"
                    ),
                    requestId = "search-1"
                ),
                result = ToolResult(
                    ok = true,
                    stdout =
                        """{"query":"retry bounded exponential backoff documentation","results":[{"title":"Retry policy docs","url":"$url","snippet":"Retry backoff and bounded exponential delays."}]}"""
                ),
                evidenceId = "web-search-1",
                now = t0
            )

        assertEquals(1, searchObservations.size)
        state = EvidenceGraphReducer.record(
            state,
            searchObservations.single()
        ).state

        assertEquals(
            EvidenceVerificationState.DISCOVERED,
            state.claims.single().verificationState
        )

        // 2. Reading the exact source upgrades source evidence to RETRIEVED.
        val readObservation =
            EvidenceGraphProjector.fromToolResult(
                task = task(),
                request = ToolRequest(
                    tool = "web.read",
                    args = mapOf("url" to url),
                    requestId = "read-1"
                ),
                result = ToolResult(
                    ok = true,
                    stdout =
                        """{"url":"$url","title":"Retry policy docs","text":"Retry backoff uses bounded exponential delays to avoid unbounded request pressure."}"""
                ),
                evidenceId = "web-read-1",
                now = t0 + 1
            ).single()

        state = EvidenceGraphReducer.record(
            state,
            readObservation
        ).state

        val sourceClaim = state.claims.first {
            it.claimKey.startsWith("source:")
        }
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            sourceClaim.verificationState
        )

        val retrievedSourceId =
            state.sources.first {
                it.kind == EvidenceSourceKind.WEB_PAGE &&
                    it.uri == url
            }.id

        // 3. A semantic model proposal can only become a PENDING candidate.
        val proposal =
            EvidenceGraphClaimPolicy.propose(
                state = state,
                proposal = EvidenceClaimProposal(
                    claimKey = semanticClaimKey,
                    statement = semanticStatement,
                    sourceIds =
                        listOf(retrievedSourceId),
                    provenance =
                        EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                    proposedAt = t0 + 2,
                    projectId = projectId,
                    projectRelevance = 0.95
                )
            )

        assertTrue(proposal.accepted)
        assertNotNull(proposal.candidateId)
        state = proposal.state

        assertEquals(
            EvidenceClaimCandidateStatus.PENDING,
            state.candidates.single().status
        )
        assertFalse(
            state.claims.any {
                it.claimKey == semanticClaimKey
            }
        )

        // 4. Only a new verified bound-source observation promotes the claim.
        val promoted =
            EvidenceGraphClaimPolicy
                .resolveWithVerifiedObservation(
                    state = state,
                    candidateId =
                        requireNotNull(proposal.candidateId),
                    observation = EvidenceObservation(
                        claimKey = semanticClaimKey,
                        statement = semanticStatement,
                        relation = EvidenceRelation.SUPPORTS,
                        sourceUri = url,
                        sourceKind =
                            EvidenceSourceKind.WEB_PAGE,
                        retrievalMethod = "web.read",
                        evidenceId =
                            "semantic-source-proof-1",
                        observedAt = t0 + 3,
                        projectId = projectId,
                        projectRelevance = 0.95,
                        verifiedToolResult = true,
                        outcomeUnknown = false
                    )
                )

        assertTrue(promoted.accepted)
        state = promoted.state

        val semanticClaim = state.claims.first {
            it.claimKey == semanticClaimKey
        }
        assertEquals(
            EvidenceVerificationState.RETRIEVED,
            semanticClaim.verificationState
        )
        assertEquals(
            EvidenceClaimCandidateStatus.PROMOTED,
            state.candidates.single().status
        )

        // 5. Binding verified evidence to a project target still grants no tool
        // authority. The normal ToolRegistry path must require confirmation.
        val binding =
            EvidenceProjectApplicationPolicy.bind(
                state = state,
                claimKey = semanticClaimKey,
                projectId = projectId,
                target = target,
                now = t0 + 4
            )

        assertTrue(binding.accepted)
        state = binding.state
        val bindingId =
            requireNotNull(binding.bindingId)

        val filePatchCall =
            AgentDecision.ToolCall(
                tool = "file.patch",
                args = mapOf(
                    "path" to target,
                    "old" to
                        "RETRY_DELAY = 1",
                    "new" to
                        "RETRY_DELAY = bounded_backoff(attempt)"
                )
            )
        val validation =
            ToolRegistry.validate(filePatchCall)

        assertTrue(validation.allowed)
        assertTrue(
            "Evidence binding must never bypass mutation confirmation",
            validation.requiresConfirmation
        )

        assertEquals(
            EvidenceApplicationStatus.PENDING,
            state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.UNKNOWN,
            state.claims.first {
                it.claimKey == semanticClaimKey
            }.outcome
        )

        // 6. A successful, known-outcome mutation proves APPLIED.
        val applied =
            EvidenceProjectApplicationPolicy
                .observeToolResult(
                    state = state,
                    bindingId = bindingId,
                    taskProjectId = projectId,
                    request = ToolRequest(
                        tool = "file.patch",
                        args = mapOf(
                            "path" to target,
                            "old" to
                                "RETRY_DELAY = 1",
                            "new" to
                                "RETRY_DELAY = bounded_backoff(attempt)"
                        ),
                        requestId = "patch-1"
                    ),
                    result = ToolResult(
                        ok = true,
                        tool = "file.patch",
                        exitCode = 0,
                        stdout = "patched"
                    ),
                    evidenceId =
                        "project-artifact-1",
                    now = t0 + 5
                )

        assertTrue(applied.accepted)
        state = applied.state

        assertEquals(
            EvidenceApplicationStatus.APPLIED,
            state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.APPLIED_TO_PROJECT,
            state.claims.first {
                it.claimKey == semanticClaimKey
            }.outcome
        )

        // 7. A successful local project test after application proves VERIFIED.
        val verified =
            EvidenceProjectApplicationPolicy
                .observeToolResult(
                    state = state,
                    bindingId = bindingId,
                    taskProjectId = projectId,
                    request = ToolRequest(
                        tool = "python.tests",
                        args = mapOf(
                            "cwd" to "scripts"
                        ),
                        requestId = "tests-1"
                    ),
                    result = ToolResult(
                        ok = true,
                        tool = "python.tests",
                        exitCode = 0,
                        stdout = "12 passed"
                    ),
                    evidenceId =
                        "project-test-1",
                    now = t0 + 6
                )

        assertTrue(verified.accepted)
        state = verified.state

        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            state.claims.first {
                it.claimKey == semanticClaimKey
            }.outcome
        )

        // 8. Persistence round-trip keeps the whole lifecycle state.
        val encoded =
            EvidenceGraphCodec.encode(state)
        val decoded =
            EvidenceGraphCodec.decode(encoded)

        assertEquals(state, decoded)
        assertFalse(
            "Evidence persistence must not contain approval state",
            encoded.contains(
                "approval",
                ignoreCase = true
            )
        )
        assertFalse(
            "Evidence persistence must not contain permission grants",
            encoded.contains(
                "permissionGrant",
                ignoreCase = true
            )
        )

        // 9. The read-only inspector explains the same final state.
        val inspector =
            EvidenceGraphInspectorPolicy.build(
                state = decoded,
                now = t0 + 7
            )

        val inspectedSemantic =
            inspector.claims.first {
                it.claimKey == semanticClaimKey
            }

        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST.name,
            inspectedSemantic.outcome
        )
        assertEquals(
            1,
            inspector.promotedCandidates
        )
        assertEquals(
            1,
            inspector.verifiedApplications
        )
        assertEquals(
            "VERIFIED",
            inspector.applications.single().status
        )
        assertEquals(
            1,
            inspector.applications.single()
                .artifactEvidenceCount
        )
        assertEquals(
            1,
            inspector.applications.single()
                .testEvidenceCount
        )
    }

    @Test
    fun lifecycleCannotSkipRetrievalPromotionOrApplicationStages() {
        var state = EvidenceGraphState()

        val searchOnly =
            EvidenceGraphProjector.fromToolResult(
                task = task(),
                request = ToolRequest(
                    tool = "web.search",
                    args = mapOf(
                        "query" to
                            "retry bounded exponential backoff documentation"
                    )
                ),
                result = ToolResult(
                    ok = true,
                    stdout =
                        """{"results":[{"title":"Retry policy docs","url":"$url","snippet":"Retry backoff uses bounded exponential delays."}]}"""
                ),
                evidenceId = "search-only",
                now = t0
            ).single()

        state = EvidenceGraphReducer.record(
            state,
            searchOnly
        ).state
        val searchSourceId =
            state.sources.single().id

        val candidate =
            EvidenceGraphClaimPolicy.propose(
                state = state,
                proposal = EvidenceClaimProposal(
                    claimKey = semanticClaimKey,
                    statement = semanticStatement,
                    sourceIds =
                        listOf(searchSourceId),
                    provenance =
                        EvidenceClaimCandidateProvenance.MODEL_PROPOSAL,
                    proposedAt = t0 + 1,
                    projectId = projectId,
                    projectRelevance = 0.9
                )
            )

        assertFalse(candidate.accepted)
        assertEquals(
            "SEARCH_ONLY_SOURCE",
            candidate.reason
        )

        val bindSearchClaim =
            EvidenceProjectApplicationPolicy.bind(
                state = state,
                claimKey =
                    searchOnly.claimKey,
                projectId = projectId,
                target = target,
                now = t0 + 2
            )

        assertFalse(bindSearchClaim.accepted)
        assertEquals(
            "CLAIM_NOT_APPLICABLE:DISCOVERED",
            bindSearchClaim.reason
        )
        assertTrue(state.applications.isEmpty())
    }
}
