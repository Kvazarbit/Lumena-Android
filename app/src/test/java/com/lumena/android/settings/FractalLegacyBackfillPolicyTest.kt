package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalLegacyBackfillPolicyTest {
    private fun verifiedExample(
        id: String,
        task: String,
        model: String,
        at: Long
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
        sourceSessionHash = task,
        tools = listOf("workspace.list", "file.read"),
        targets = listOf("", "path=$id.txt"),
        evidenceIds = listOf("evidence-$id"),
        updatedAt = at,
        surprise = 0.6,
        text = "verified $id",
        contributorModelIds = listOf(model),
        scopeHash = "scope-a",
        outcomes = listOf(true, true)
    )

    @Test
    fun migrationImportsVerifiedHistoryOnceWithoutChangingCoordinatorSource() {
        val coordinator = CoordinatorEpisodeState(
            learnedExamples = listOf(
                verifiedExample("a", "task-a", "model-a", 1_000L),
                verifiedExample("b", "task-b", "model-b", 2_000L),
                verifiedExample("bad", "task-bad", "model-x", 3_000L)
                    .copy(evidenceIds = emptyList())
            )
        )
        val sourceBefore = coordinator.copy()

        val first = FractalLegacyBackfillPolicy.migrate(
            canvas = FractalExperienceCanvasState(),
            coordinator = coordinator
        )

        assertFalse(first.alreadyApplied)
        assertEquals(2, first.sourceExamples)
        assertEquals(2, first.importedRecords)
        assertEquals(1, first.state.legacyBackfillVersion)
        assertEquals(
            setOf("a", "b"),
            first.state.records.map { it.id }.toSet()
        )
        assertTrue(
            first.state.records.all {
                it.origin == FractalExperienceOrigin.LEGACY_BACKFILL
            }
        )
        assertTrue(
            first.state.nodes.all {
                it.stage == FractalExperienceStage.SHADOW
            }
        )
        assertEquals(sourceBefore, coordinator)

        val second = FractalLegacyBackfillPolicy.migrate(
            canvas = first.state,
            coordinator = coordinator.copy(
                learnedExamples = coordinator.learnedExamples +
                    verifiedExample("c", "task-c", "model-c", 4_000L)
            )
        )

        assertTrue(second.alreadyApplied)
        assertEquals(0, second.sourceExamples)
        assertEquals(0, second.importedRecords)
        assertEquals(first.state, second.state)
    }

    @Test
    fun emptyVerifiedHistoryStillCompletesMigrationWithoutInventingExperience() {
        val result = FractalLegacyBackfillPolicy.migrate(
            canvas = FractalExperienceCanvasState(),
            coordinator = CoordinatorEpisodeState()
        )

        assertFalse(result.alreadyApplied)
        assertEquals(0, result.sourceExamples)
        assertEquals(0, result.importedRecords)
        assertEquals(1, result.state.legacyBackfillVersion)
        assertTrue(result.state.records.isEmpty())
        assertTrue(result.state.nodes.isEmpty())
    }
}
