package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalCausalExperiencePolicyTest {
    private fun record(
        id: String,
        kind: CoordinatorExampleKind,
        task: String,
        outcomes: List<Boolean>,
        origin: FractalExperienceOrigin = FractalExperienceOrigin.LIVE,
        scope: String = "scope-a",
        tools: List<String> = listOf("python.run", "file.read", "python.run"),
        evidence: List<String> = listOf("evidence")
    ) = FractalExampleRecord(
        id = id,
        kind = kind,
        sourceTaskHash = task,
        tools = tools,
        targets = listOf("script=probe.py", "path=result.txt"),
        outcomes = outcomes,
        evidenceIds = evidence,
        contributorModelIds = listOf("chatgpt"),
        scopeHash = scope,
        updatedAt = 1_000L + id.length,
        summary = "fixture",
        origin = origin
    )

    @Test
    fun recoveryBecomesExplicitBadPathRecoveryChainWithoutInventedCause() {
        val link = FractalCausalExperiencePolicy.links(
            listOf(
                record(
                    id = "recovered",
                    kind = CoordinatorExampleKind.RECOVERY,
                    task = "task-a",
                    outcomes = listOf(false, true, true)
                )
            )
        ).single()

        assertEquals("python.run", link.failedTool)
        assertEquals(0, link.failedStepIndex)
        assertEquals(
            listOf("file.read", "python.run"),
            link.recoveryTools
        )
        assertEquals(
            listOf(true, true),
            link.recoveryOutcomes
        )
        assertEquals(
            FractalCausalResolution.RECOVERED,
            link.resolution
        )
        assertEquals(
            FractalCausalCauseKnowledge.UNKNOWN_NOT_CAPTURED,
            link.causeKnowledge
        )

        val prompt =
            FractalCausalExperiencePolicy.formatForPrompt(link)
        assertTrue(prompt.contains("BAD_PATH=python.run[failed]"))
        assertTrue(prompt.contains("WHY_FAILED=UNKNOWN_NOT_CAPTURED"))
        assertTrue(prompt.contains("RECOVERY=file.read[ok] -> python.run[ok]"))
        assertTrue(prompt.contains("advisory only"))
    }

    @Test
    fun failedRetryRemainsUnresolvedAndIsNeverRewrittenAsRecovery() {
        val link = FractalCausalExperiencePolicy.links(
            listOf(
                record(
                    id = "failed-again",
                    kind = CoordinatorExampleKind.FAILED_RECOVERY,
                    task = "task-a",
                    outcomes = listOf(false, true, false)
                )
            )
        ).single()

        assertEquals(
            FractalCausalResolution.FAILED_AGAIN,
            link.resolution
        )
        assertTrue(
            FractalCausalExperiencePolicy
                .formatForPrompt(link)
                .contains("CAUSAL UNRESOLVED FAILURE")
        )
    }

    @Test
    fun legacyRecoveryNeedsIndependentLiveRecoveryToCountAsRevalidated() {
        val legacy = record(
            id = "legacy",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "old-task",
            outcomes = listOf(false, true, true),
            origin = FractalExperienceOrigin.LEGACY_BACKFILL
        )
        var stats = FractalCausalExperiencePolicy.stats(
            listOf(legacy)
        )
        assertEquals(0, stats.revalidatedPatterns)
        assertEquals(0, stats.liveLinks)

        val live = record(
            id = "live",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "new-task",
            outcomes = listOf(false, true, true),
            origin = FractalExperienceOrigin.LIVE
        )
        stats = FractalCausalExperiencePolicy.stats(
            listOf(legacy, live)
        )

        assertEquals(2, stats.links)
        assertEquals(2, stats.recovered)
        assertEquals(1, stats.liveLinks)
        assertEquals(1, stats.legacyLinks)
        assertEquals(1, stats.revalidatedPatterns)
    }

    @Test
    fun contradictoryRecoveryPatternDoesNotCountAsRevalidated() {
        val legacy = record(
            id = "legacy-ok",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "old-task",
            outcomes = listOf(false, true, true),
            origin = FractalExperienceOrigin.LEGACY_BACKFILL
        )
        val liveFailure = record(
            id = "live-bad",
            kind = CoordinatorExampleKind.FAILED_RECOVERY,
            task = "new-task",
            outcomes = listOf(false, true, false),
            origin = FractalExperienceOrigin.LIVE
        )

        val stats = FractalCausalExperiencePolicy.stats(
            listOf(legacy, liveFailure)
        )
        assertEquals(1, stats.unresolved)
        assertEquals(0, stats.revalidatedPatterns)
    }

    @Test
    fun retrievalIsScopedAndVerifiedSequenceIsNotPretendedToBeCausalRecovery() {
        val a = record(
            id = "scope-a-recovery",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "task-a",
            outcomes = listOf(false, true, true),
            scope = "scope-a"
        )
        val b = record(
            id = "scope-b-recovery",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "task-b",
            outcomes = listOf(false, true, true),
            scope = "scope-b"
        )
        val ordinary = record(
            id = "ordinary",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            task = "task-c",
            outcomes = listOf(true, true, true),
            scope = "scope-a"
        )

        val links =
            FractalCausalExperiencePolicy.links(
                listOf(a, b, ordinary)
            )
        assertEquals(2, links.size)

        val relevant =
            FractalCausalExperiencePolicy.relevant(
                records = listOf(a, b, ordinary),
                query = "python.run",
                scopeHash = "scope-a",
                limit = 8
            )
        assertEquals(1, relevant.size)
        assertEquals("scope-a", relevant.single().scopeHash)
    }

    @Test
    fun evidenceFreeRecordCannotBecomeCausalMemory() {
        val invalid = record(
            id = "no-evidence",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "task-a",
            outcomes = listOf(false, true, true),
            evidence = emptyList()
        )

        assertTrue(
            FractalCausalExperiencePolicy
                .links(listOf(invalid))
                .isEmpty()
        )
        assertFalse(
            FractalCausalExperiencePolicy
                .formatForPrompt(
                    FractalCausalExperiencePolicy.links(
                        listOf(
                            record(
                                id = "valid",
                                kind = CoordinatorExampleKind.RECOVERY,
                                task = "task-b",
                                outcomes = listOf(false, true, true)
                            )
                        )
                    ).single()
                )
                .contains("permission")
        )
    }

    @Test
    fun structuredToolFailureBecomesEvidenceGroundedWhyFailed() {
        val source = record(
            id = "structured-cause",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "task-structured",
            outcomes = listOf(false, true, true)
        ).copy(
            failureClasses = listOf("INVALID_INPUT", null, null),
            errorCodes = listOf("PYTHON_SCRIPT_REQUIRED", null, null),
            retryableFlags = listOf(true, null, null),
            dependencies = listOf("tool-schema", null, null)
        )

        val link = FractalCausalExperiencePolicy
            .links(listOf(source))
            .single()

        assertEquals(
            FractalCausalCauseKnowledge.STRUCTURED_TOOL_FAILURE,
            link.causeKnowledge
        )
        assertEquals("INVALID_INPUT", link.causeFailureClass)
        assertEquals("PYTHON_SCRIPT_REQUIRED", link.causeErrorCode)
        assertEquals(true, link.causeRetryable)
        assertEquals("tool-schema", link.causeDependency)

        val prompt =
            FractalCausalExperiencePolicy.formatForPrompt(link)
        assertTrue(
            prompt.contains(
                "WHY_FAILED=STRUCTURED_TOOL_FAILURE"
            )
        )
        assertTrue(prompt.contains("class=INVALID_INPUT"))
        assertTrue(prompt.contains("code=PYTHON_SCRIPT_REQUIRED"))
        assertTrue(prompt.contains("retryable=true"))
        assertTrue(prompt.contains("dependency=tool-schema"))
        assertFalse(prompt.contains("stderr", ignoreCase = true))

        val retrieved = FractalCausalExperiencePolicy.relevant(
            records = listOf(source),
            query = "PYTHON_SCRIPT_REQUIRED",
            scopeHash = "scope-a",
            limit = 4
        )
        assertEquals(1, retrieved.size)
        assertEquals(link.id, retrieved.single().id)
    }

    @Test
    fun missingStructuredFailureMetadataStillFailsClosedToUnknownCause() {
        val link = FractalCausalExperiencePolicy
            .links(
                listOf(
                    record(
                        id = "unknown-cause",
                        kind = CoordinatorExampleKind.RECOVERY,
                        task = "task-unknown",
                        outcomes = listOf(false, true, true)
                    )
                )
            )
            .single()

        assertEquals(
            FractalCausalCauseKnowledge.UNKNOWN_NOT_CAPTURED,
            link.causeKnowledge
        )
        assertEquals(null, link.causeFailureClass)
        assertEquals(null, link.causeErrorCode)
        assertEquals(null, link.causeRetryable)
        assertEquals(null, link.causeDependency)
    }


    @Test
    fun endToEndVerifiedToolFailureProjectsIntoCausalRecovery() {
        val events = listOf(
            CoordinatorEpisodeEvent(
                id = "e2e-fail",
                sessionId = "e2e-session",
                taskId = "e2e-task",
                tool = "python.run",
                target = "script=missing.py",
                ok = false,
                experienceId = "ev-e2e-fail",
                at = 100L,
                surprise = 0.9,
                scopeHash = "scope-a",
                outcomeKnown = true,
                failureClass = "INVALID_INPUT",
                errorCode = "PYTHON_SCRIPT_REQUIRED",
                retryable = true,
                dependency = "tool-schema"
            ),
            CoordinatorEpisodeEvent(
                id = "e2e-inspect",
                sessionId = "e2e-session",
                taskId = "e2e-task",
                tool = "workspace.list",
                target = "",
                ok = true,
                experienceId = "ev-e2e-inspect",
                at = 200L,
                surprise = 0.8,
                scopeHash = "scope-a"
            ),
            CoordinatorEpisodeEvent(
                id = "e2e-ok",
                sessionId = "e2e-session",
                taskId = "e2e-task",
                tool = "python.run",
                target = "script=missing.py",
                ok = true,
                experienceId = "ev-e2e-ok",
                at = 300L,
                surprise = 1.0,
                scopeHash = "scope-a"
            )
        )
        val coordinator = events.fold(CoordinatorEpisodeState()) { state, event ->
            CoordinatorExperiencePolicy.record(state, event)
        }
        val verified =
            CoordinatorExperiencePolicy.allVerifiedExamples(coordinator)
        val canvas = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            verified
        )
        val link = FractalCausalExperiencePolicy
            .links(canvas.records)
            .first { it.resolution == FractalCausalResolution.RECOVERED }

        assertEquals("python.run", link.failedTool)
        assertEquals(
            FractalCausalCauseKnowledge.STRUCTURED_TOOL_FAILURE,
            link.causeKnowledge
        )
        assertEquals("INVALID_INPUT", link.causeFailureClass)
        assertEquals("PYTHON_SCRIPT_REQUIRED", link.causeErrorCode)
        assertEquals(
            listOf("workspace.list", "python.run"),
            link.recoveryTools
        )
        assertEquals(listOf(true, true), link.recoveryOutcomes)
        assertTrue(link.evidenceIds.contains("ev-e2e-fail"))
        assertTrue(link.evidenceIds.contains("ev-e2e-ok"))
    }


    @Test
    fun differentStructuredFailureCauseDoesNotCountAsRevalidatedPattern() {
        val legacy = record(
            id = "legacy-structured",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "old-task-structured",
            outcomes = listOf(false, true, true),
            origin = FractalExperienceOrigin.LEGACY_BACKFILL
        ).copy(
            failureClasses = listOf("INVALID_INPUT", null, null),
            errorCodes = listOf("PYTHON_SCRIPT_REQUIRED", null, null),
            retryableFlags = listOf(true, null, null),
            dependencies = listOf("tool-schema", null, null)
        )
        val live = record(
            id = "live-structured",
            kind = CoordinatorExampleKind.RECOVERY,
            task = "new-task-structured",
            outcomes = listOf(false, true, true),
            origin = FractalExperienceOrigin.LIVE
        ).copy(
            failureClasses = listOf("RUNTIME_ERROR", null, null),
            errorCodes = listOf("PYTHON_RUNTIME_FAILURE", null, null),
            retryableFlags = listOf(true, null, null),
            dependencies = listOf("python-runtime", null, null)
        )

        val stats = FractalCausalExperiencePolicy.stats(
            listOf(legacy, live)
        )

        assertEquals(2, stats.recovered)
        assertEquals(1, stats.liveLinks)
        assertEquals(1, stats.legacyLinks)
        assertEquals(0, stats.revalidatedPatterns)
    }

}
