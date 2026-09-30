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
}
