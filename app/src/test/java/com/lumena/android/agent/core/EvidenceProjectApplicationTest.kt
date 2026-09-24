package com.lumena.android.agent.core

import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceProjectApplicationTest {
    private val now = 1_800_000_000_000L
    private val claimKey = "android-workmanager-persistent"
    private val target = "app/src/main/python/worker.py"

    private fun retrievedState(
        observedAt: Long = now
    ): EvidenceGraphState =
        EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = claimKey,
                statement =
                    "Android WorkManager supports persistent background work.",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://developer.android.com/workmanager",
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "web-source-1",
                observedAt = observedAt,
                projectId = "lumena",
                projectRelevance = 0.9
            )
        ).state

    private fun bind(
        state: EvidenceGraphState,
        projectId: String = "lumena",
        bindTarget: String = target,
        at: Long = now + 1
    ): EvidenceApplicationUpdate =
        EvidenceProjectApplicationPolicy.bind(
            state = state,
            claimKey = claimKey,
            projectId = projectId,
            target = bindTarget,
            now = at
        )

    @Test
    fun discoveredSearchSnippetCannotBeBoundToProjectMutation() {
        val discovered = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = claimKey,
                statement =
                    "Android WorkManager supports persistent background work.",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://search.example/workmanager",
                sourceKind = EvidenceSourceKind.SEARCH_SNIPPET,
                retrievalMethod = "web.search",
                evidenceId = "search-only",
                observedAt = now
            )
        ).state

        val update = bind(discovered)

        assertFalse(update.accepted)
        assertEquals(
            "CLAIM_NOT_APPLICABLE:DISCOVERED",
            update.reason
        )
    }

    @Test
    fun staleOrContestedClaimCannotBeBound() {
        val stale = bind(
            retrievedState(
                observedAt =
                    now -
                        EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS -
                        10
            ),
            at = now
        )
        assertFalse(stale.accepted)
        assertEquals(
            "CLAIM_NOT_APPLICABLE:STALE",
            stale.reason
        )

        var contested = retrievedState()
        contested = EvidenceGraphReducer.record(
            contested,
            EvidenceObservation(
                claimKey = claimKey,
                statement =
                    "Android WorkManager does not support persistent background work.",
                relation = EvidenceRelation.CONTRADICTS,
                sourceUri = "https://independent.example/workmanager",
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "contradiction",
                observedAt = now + 1
            )
        ).state

        val contestedBind = bind(
            contested,
            at = now + 2
        )
        assertFalse(contestedBind.accepted)
        assertEquals(
            "CLAIM_NOT_APPLICABLE:CONTESTED",
            contestedBind.reason
        )
    }

    @Test
    fun bindingVerifiedClaimIsAdvisoryAndDoesNotChangeOutcome() {
        val state = retrievedState()
        val update = bind(state)

        assertTrue(update.accepted)
        val binding = update.state.applications.single()
        assertEquals(
            EvidenceApplicationStatus.PENDING,
            binding.status
        )
        assertEquals(target, binding.target)
        assertEquals(
            EvidenceProjectOutcome.UNKNOWN,
            update.state.claims.single().outcome
        )
    }

    @Test
    fun failedOrUnknownMutationCannotMarkClaimApplied() {
        val bound = bind(retrievedState())
        val bindingId = bound.bindingId!!

        val failed = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bindingId,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to target,
                        "old" to "a",
                        "new" to "b"
                    )
                ),
                result = ToolResult(
                    ok = false,
                    error = "patch failed"
                ),
                evidenceId = "patch-failed",
                now = now + 2
            )

        assertFalse(failed.accepted)
        assertEquals("TOOL_RESULT_FAILED", failed.reason)

        val unknown = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bindingId,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to target,
                        "old" to "a",
                        "new" to "b"
                    )
                ),
                result = ToolResult(
                    ok = false,
                    outcomeUnknown = true
                ),
                evidenceId = "patch-unknown",
                now = now + 3
            )

        assertFalse(unknown.accepted)
        assertEquals("UNKNOWN_EFFECT", unknown.reason)
        assertEquals(
            EvidenceApplicationStatus.PENDING,
            unknown.state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.UNKNOWN,
            unknown.state.claims.single().outcome
        )
    }

    @Test
    fun successfulBoundFilePatchMarksAppliedWithArtifactProof() {
        val bound = bind(retrievedState())

        val applied = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to "./app/src/main/python/../python/worker.py",
                        "old" to "a",
                        "new" to "b"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "patch-ok",
                now = now + 2
            )

        assertTrue(applied.accepted)
        assertEquals(
            EvidenceApplicationStatus.APPLIED,
            applied.state.applications.single().status
        )
        assertEquals(
            listOf("patch-ok"),
            applied.state.applications.single()
                .artifactEvidenceIds
        )
        assertEquals(
            EvidenceProjectOutcome.APPLIED_TO_PROJECT,
            applied.state.claims.single().outcome
        )
        assertEquals(
            listOf("patch-ok"),
            applied.state.claims.single()
                .outcomeEvidenceIds
        )
    }

    @Test
    fun mismatchedMutationTargetCannotMarkApplied() {
        val bound = bind(retrievedState())

        val update = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.write",
                    args = mapOf(
                        "path" to "some/other/file.py",
                        "content" to "x"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "write-other",
                now = now + 2
            )

        assertFalse(update.accepted)
        assertEquals("TARGET_MISMATCH", update.reason)
        assertEquals(
            EvidenceProjectOutcome.UNKNOWN,
            update.state.claims.single().outcome
        )
    }

    @Test
    fun verificationCannotHappenBeforeApplication() {
        val bound = bind(retrievedState())

        val verify = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "python.syntax_check",
                    args = mapOf("script" to target)
                ),
                result = ToolResult(ok = true),
                evidenceId = "syntax-before-apply",
                now = now + 2
            )

        assertFalse(verify.accepted)
        assertEquals("VERIFY_BEFORE_APPLY", verify.reason)
    }

    @Test
    fun successfulSyntaxCheckAfterApplicationMarksVerifiedByTest() {
        val bound = bind(retrievedState())
        val applied = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.write",
                    args = mapOf(
                        "path" to target,
                        "content" to "print('ok')"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "write-ok",
                now = now + 2
            )
        assertTrue(applied.accepted)

        val verified = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = applied.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "python.syntax_check",
                    args = mapOf(
                        "script" to "./$target"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "syntax-ok",
                now = now + 3
            )

        assertTrue(verified.accepted)
        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            verified.state.applications.single().status
        )
        assertEquals(
            listOf("syntax-ok"),
            verified.state.applications.single()
                .testEvidenceIds
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            verified.state.claims.single().outcome
        )
    }

    @Test
    fun projectTestsCanVerifyAppliedBindingWithinSameProject() {
        val bound = bind(retrievedState())
        val applied = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to target,
                        "old" to "a",
                        "new" to "b"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "patch-ok",
                now = now + 2
            )

        val verified = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = applied.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "python.tests",
                    args = mapOf("cwd" to "app")
                ),
                result = ToolResult(ok = true),
                evidenceId = "tests-ok",
                now = now + 3
            )

        assertTrue(verified.accepted)
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            verified.state.claims.single().outcome
        )
    }

    @Test
    fun projectScopeMismatchAndUnrelatedToolsCannotBecomeProof() {
        val bound = bind(retrievedState())

        val wrongProject = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "other-project",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to target,
                        "old" to "a",
                        "new" to "b"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "patch-ok",
                now = now + 2
            )
        assertFalse(wrongProject.accepted)
        assertEquals(
            "PROJECT_SCOPE_MISMATCH",
            wrongProject.reason
        )

        val gitCommit = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "git.commit",
                    args = mapOf(
                        "cwd" to ".",
                        "message" to "commit"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "commit-ok",
                now = now + 3
            )
        assertFalse(gitCommit.accepted)
        assertEquals(
            "TOOL_NOT_PROJECT_PROOF",
            gitCommit.reason
        )
    }

    @Test
    fun projectOutcomeDoesNotRefreshExternalEvidenceFreshness() {
        val sourceObservedAt = now
        val state = retrievedState(
            observedAt = sourceObservedAt
        )
        val bound = bind(
            state,
            at = now + 1
        )
        val applied = EvidenceProjectApplicationPolicy
            .observeToolResult(
                state = bound.state,
                bindingId = bound.bindingId!!,
                taskProjectId = "lumena",
                request = ToolRequest(
                    tool = "file.write",
                    args = mapOf(
                        "path" to target,
                        "content" to "x"
                    )
                ),
                result = ToolResult(ok = true),
                evidenceId = "write-later",
                now =
                    now +
                        EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS +
                        100
            )

        assertTrue(applied.accepted)
        val claim = applied.state.claims.single()
        assertEquals(
            sourceObservedAt,
            claim.lastObservedAt
        )
        assertEquals(
            EvidenceVerificationState.STALE,
            EvidenceGraphReducer.effectiveVerificationState(
                claim,
                now =
                    now +
                        EvidenceGraphReducer.DEFAULT_STALE_AFTER_MS +
                        101
            )
        )
    }

    @Test
    fun verifiedOutcomeCannotRegressBackToApplied() {
        var state = retrievedState()
        state = EvidenceGraphReducer.applyProjectOutcome(
            state,
            claimKey,
            EvidenceProjectOutcome.APPLIED_TO_PROJECT,
            EvidenceOutcomeProof(
                kind =
                    EvidenceOutcomeProofKind.PROJECT_ARTIFACT,
                evidenceId = "artifact",
                at = now + 1
            )
        ).state
        state = EvidenceGraphReducer.applyProjectOutcome(
            state,
            claimKey,
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            EvidenceOutcomeProof(
                kind =
                    EvidenceOutcomeProofKind.PROJECT_TEST,
                evidenceId = "test",
                at = now + 2
            )
        ).state

        val regression = EvidenceGraphReducer
            .applyProjectOutcome(
                state,
                claimKey,
                EvidenceProjectOutcome.APPLIED_TO_PROJECT,
                EvidenceOutcomeProof(
                    kind =
                        EvidenceOutcomeProofKind.PROJECT_ARTIFACT,
                    evidenceId = "artifact-late",
                    at = now + 3
                )
            )

        assertFalse(regression.accepted)
        assertEquals(
            "OUTCOME_REGRESSION",
            regression.reason
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            regression.state.claims.single().outcome
        )
    }
}
