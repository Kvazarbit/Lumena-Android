package com.lumena.android.agent.core

import com.lumena.android.agent.local.ToolRequest
import com.lumena.android.agent.local.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvidenceProjectOutcomeRouterTest {
    private val now = 1_800_000_000_000L

    private fun claimState(
        claimKey: String,
        uri: String,
        target: String
    ): EvidenceGraphState {
        val retrieved = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = claimKey,
                statement = "Verified technical evidence for $claimKey",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = uri,
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "source-$claimKey",
                observedAt = now,
                projectId = "lumena",
                projectRelevance = 0.9
            )
        ).state

        return EvidenceProjectApplicationPolicy.bind(
            state = retrieved,
            claimKey = claimKey,
            projectId = "lumena",
            target = target,
            now = now + 1
        ).state
    }

    @Test
    fun exactMutationTargetRoutesOnlyMatchingBinding() {
        var state = claimState(
            claimKey = "claim-a",
            uri = "https://a.example/docs",
            target = "app/a.py"
        )
        val second = claimState(
            claimKey = "claim-b",
            uri = "https://b.example/docs",
            target = "app/b.py"
        )
        state = state.copy(
            claims = (state.claims + second.claims)
                .distinctBy { it.id },
            sources = (state.sources + second.sources)
                .distinctBy { it.id },
            applications =
                state.applications + second.applications
        )

        val ids = EvidenceProjectOutcomeRouter
            .matchingBindingIds(
                state = state,
                projectId = "lumena",
                request = ToolRequest(
                    tool = "file.patch",
                    args = mapOf(
                        "path" to "./app/./a.py",
                        "old" to "x",
                        "new" to "y"
                    )
                )
            )

        assertEquals(1, ids.size)
        assertEquals(
            state.applications.first {
                it.target == "app/a.py"
            }.id,
            ids.single()
        )
    }

    @Test
    fun projectTestsRouteOnlyBindingsWithinTestScope() {
        var state = claimState(
            claimKey = "claim-app",
            uri = "https://app.example/docs",
            target = "app/src/worker.py"
        )
        val other = claimState(
            claimKey = "claim-tools",
            uri = "https://tools.example/docs",
            target = "tools/script.py"
        )
        state = state.copy(
            claims = (state.claims + other.claims)
                .distinctBy { it.id },
            sources = (state.sources + other.sources)
                .distinctBy { it.id },
            applications =
                state.applications + other.applications
        )

        val ids = EvidenceProjectOutcomeRouter
            .matchingBindingIds(
                state = state,
                projectId = "lumena",
                request = ToolRequest(
                    tool = "python.tests",
                    args = mapOf("cwd" to "app")
                )
            )

        assertEquals(1, ids.size)
        assertEquals(
            state.applications.first {
                it.target == "app/src/worker.py"
            }.id,
            ids.single()
        )
    }

    @Test
    fun wrongProjectAndUnrelatedToolsRouteNothing() {
        val state = claimState(
            claimKey = "claim-a",
            uri = "https://a.example/docs",
            target = "app/a.py"
        )

        val wrongProject = EvidenceProjectOutcomeRouter
            .matchingBindingIds(
                state = state,
                projectId = "other",
                request = ToolRequest(
                    tool = "file.write",
                    args = mapOf(
                        "path" to "app/a.py",
                        "content" to "x"
                    )
                )
            )
        assertTrue(wrongProject.isEmpty())

        val unrelated = EvidenceProjectOutcomeRouter
            .matchingBindingIds(
                state = state,
                projectId = "lumena",
                request = ToolRequest(
                    tool = "git.commit",
                    args = mapOf(
                        "cwd" to ".",
                        "message" to "x"
                    )
                )
            )
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun runtimeLifecycleAdvancesOnlyExistingBinding() {
        val initial = claimState(
            claimKey = "claim-lifecycle",
            uri = "https://docs.example/lifecycle",
            target = "app/worker.py"
        )
        val bindingId = initial.applications.single().id

        val mutationRequest = ToolRequest(
            tool = "file.write",
            args = mapOf(
                "path" to "app/worker.py",
                "content" to "print('ok')"
            )
        )
        val mutationIds =
            EvidenceProjectOutcomeRouter.matchingBindingIds(
                state = initial,
                projectId = "lumena",
                request = mutationRequest
            )
        assertEquals(listOf(bindingId), mutationIds)

        val applied =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = initial,
                bindingId = bindingId,
                taskProjectId = "lumena",
                request = mutationRequest,
                result = ToolResult(ok = true),
                evidenceId = "artifact-ok",
                now = now + 2
            )
        assertTrue(applied.accepted)
        assertEquals(
            EvidenceApplicationStatus.APPLIED,
            applied.state.applications.single().status
        )

        val testRequest = ToolRequest(
            tool = "python.tests",
            args = mapOf("cwd" to "app")
        )
        val testIds =
            EvidenceProjectOutcomeRouter.matchingBindingIds(
                state = applied.state,
                projectId = "lumena",
                request = testRequest
            )
        assertEquals(listOf(bindingId), testIds)

        val verified =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = applied.state,
                bindingId = bindingId,
                taskProjectId = "lumena",
                request = testRequest,
                result = ToolResult(ok = true),
                evidenceId = "tests-ok",
                now = now + 3
            )

        assertTrue(verified.accepted)
        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            verified.state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            verified.state.claims.single().outcome
        )
        assertEquals(
            listOf("artifact-ok"),
            verified.state.applications.single()
                .artifactEvidenceIds
        )
        assertEquals(
            listOf("tests-ok"),
            verified.state.applications.single()
                .testEvidenceIds
        )
    }

    @Test
    fun failedMatchingResultDoesNotAdvanceBinding() {
        val state = claimState(
            claimKey = "claim-failed",
            uri = "https://docs.example/failed",
            target = "app/fail.py"
        )
        val bindingId = state.applications.single().id
        val request = ToolRequest(
            tool = "file.patch",
            args = mapOf(
                "path" to "app/fail.py",
                "old" to "a",
                "new" to "b"
            )
        )

        val ids = EvidenceProjectOutcomeRouter
            .matchingBindingIds(
                state = state,
                projectId = "lumena",
                request = request
            )
        assertEquals(listOf(bindingId), ids)

        val update =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = state,
                bindingId = bindingId,
                taskProjectId = "lumena",
                request = request,
                result = ToolResult(
                    ok = false,
                    error = "fixture failure"
                ),
                evidenceId = "failed-result",
                now = now + 2
            )

        assertFalse(update.accepted)
        assertEquals(
            EvidenceApplicationStatus.PENDING,
            update.state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.UNKNOWN,
            update.state.claims.single().outcome
        )
    }

    @Test
    fun successfulProjectMutationAutoBindsVerifiedEvidenceThenRoutesAppliedAndVerified() {
        val retrieved = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = "pathlib-mkdir-parents",
                statement =
                    "Path.mkdir with parents true creates missing parent directories.",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://docs.python.org/pathlib",
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "web-read-ok",
                observedAt = now,
                projectId = "demo_project",
                projectRelevance = 0.95
            )
        ).state

        val mutationRequest = ToolRequest(
            tool = "file.write",
            args = mapOf(
                "path" to "demo_project/evidence_step8.py",
                "content" to "from pathlib import Path"
            )
        )
        val mutationResult = ToolResult(
            ok = true,
            tool = "file.write"
        )

        val auto =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = retrieved,
                    projectId = "demo_project",
                    request = mutationRequest,
                    result = mutationResult,
                    now = now + 1
                )

        assertEquals(1, auto.bindingIds.size)
        assertEquals(1, auto.state.applications.size)
        assertEquals(
            EvidenceApplicationStatus.PENDING,
            auto.state.applications.single().status
        )
        assertEquals(
            "demo_project/evidence_step8.py",
            auto.state.applications.single().target
        )

        val bindingId = auto.bindingIds.single()
        assertEquals(
            listOf(bindingId),
            EvidenceProjectOutcomeRouter.matchingBindingIds(
                state = auto.state,
                projectId = "demo_project",
                request = mutationRequest
            )
        )

        val applied =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = auto.state,
                bindingId = bindingId,
                taskProjectId = "demo_project",
                request = mutationRequest,
                result = mutationResult,
                evidenceId = "mutation-ok",
                now = now + 2
            )

        assertTrue(applied.accepted)
        assertEquals(
            EvidenceApplicationStatus.APPLIED,
            applied.state.applications.single().status
        )

        val testRequest = ToolRequest(
            tool = "python.tests",
            args = mapOf("cwd" to "demo_project")
        )
        val verified =
            EvidenceProjectApplicationPolicy.observeToolResult(
                state = applied.state,
                bindingId = bindingId,
                taskProjectId = "demo_project",
                request = testRequest,
                result = ToolResult(
                    ok = true,
                    tool = "python.tests"
                ),
                evidenceId = "pytest-ok",
                now = now + 3
            )

        assertTrue(verified.accepted)
        assertEquals(
            EvidenceApplicationStatus.VERIFIED,
            verified.state.applications.single().status
        )
        assertEquals(
            EvidenceProjectOutcome.VERIFIED_BY_TEST,
            verified.state.claims.single().outcome
        )
    }

    @Test
    fun autoBindingRejectsFailedUnknownCrossProjectAndSearchOnlyMutations() {
        val searchOnly = EvidenceGraphReducer.record(
            EvidenceGraphState(),
            EvidenceObservation(
                claimKey = "search-only",
                statement = "Search snippet only",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://search.example/result",
                sourceKind = EvidenceSourceKind.SEARCH_SNIPPET,
                retrievalMethod = "web.search",
                evidenceId = "search-evidence",
                observedAt = now,
                projectId = "demo_project",
                projectRelevance = 1.0
            )
        ).state

        val request = ToolRequest(
            tool = "file.write",
            args = mapOf(
                "path" to "demo_project/file.py",
                "content" to "x"
            )
        )

        assertTrue(
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = searchOnly,
                    projectId = "demo_project",
                    request = request,
                    result = ToolResult(ok = true),
                    now = now + 1
                )
                .bindingIds
                .isEmpty()
        )

        val retrieved = EvidenceGraphReducer.record(
            searchOnly,
            EvidenceObservation(
                claimKey = "verified-project-evidence",
                statement = "Verified project evidence",
                relation = EvidenceRelation.SUPPORTS,
                sourceUri = "https://docs.example/project",
                sourceKind = EvidenceSourceKind.WEB_PAGE,
                retrievalMethod = "web.read",
                evidenceId = "read-evidence",
                observedAt = now + 1,
                projectId = "demo_project",
                projectRelevance = 0.9
            )
        ).state

        val failed =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = retrieved,
                    projectId = "demo_project",
                    request = request,
                    result = ToolResult(
                        ok = false,
                        error = "write failed"
                    ),
                    now = now + 2
                )
        assertTrue(failed.bindingIds.isEmpty())

        val unknown =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = retrieved,
                    projectId = "demo_project",
                    request = request,
                    result = ToolResult(
                        ok = false,
                        outcomeUnknown = true,
                        error = "transport lost"
                    ),
                    now = now + 2
                )
        assertTrue(unknown.bindingIds.isEmpty())

        val crossProject =
            EvidenceAutomaticProjectBindingPolicy
                .bindForSuccessfulMutation(
                    state = retrieved,
                    projectId = "demo_project",
                    request = ToolRequest(
                        tool = "file.write",
                        args = mapOf(
                            "path" to "other_project/file.py",
                            "content" to "x"
                        )
                    ),
                    result = ToolResult(ok = true),
                    now = now + 2
                )
        assertTrue(crossProject.bindingIds.isEmpty())
    }

}
