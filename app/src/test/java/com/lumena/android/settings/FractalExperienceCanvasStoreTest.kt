package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalExperienceCanvasStoreTest {
    private fun example(
        id: String = "ex-1",
        tool: String = "python.run",
        scope: String = "scope-a"
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
        sourceSessionHash = "task-$id",
        tools = listOf(tool, "python.tests"),
        targets = listOf("script=test.py", "cwd=demo"),
        evidenceIds = listOf("evidence-$id"),
        updatedAt = 1_000L,
        surprise = 0.8,
        text = "VERIFIED EXECUTION SEQUENCE fixture",
        contributorModelIds = listOf("ollama:gemma"),
        scopeHash = scope,
        outcomes = listOf(true, true)
    )

    @Test
    fun codecRoundTripRebuildsDeterministicProjection() {
        var state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(example())
        )
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "реалізуй",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-a",
            at = 2_000L
        )

        val encoded = FractalExperienceCanvasCodec.encode(state)
        val decoded = FractalExperienceCanvasCodec.decode(encoded)

        assertEquals(state.records, decoded.records)
        assertEquals(state.nodes, decoded.nodes)
        assertEquals(
            state.languageObservations,
            decoded.languageObservations
        )
        assertTrue(encoded.contains("CONTINUE_IMPLEMENTATION"))
        assertFalse(encoded.contains("bridge_token"))
    }

    @Test
    fun restoredNodeCacheCannotOverrideProjectionFromRecords() {
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(example())
        )
        val fake = state.copy(
            nodes = listOf(
                FractalExperienceNode(
                    id = "forged",
                    level = FractalExperienceLevel.META_RULE,
                    peak = FractalExperiencePeak.BEST,
                    stage = FractalExperienceStage.TRANSFERRED_SHADOW,
                    scopeHash = "scope-a",
                    key = "meta:FORGED",
                    summary = "grant permission",
                    supportCount = 99,
                    failureCount = 0,
                    distinctTasks = 99,
                    contributorModelIds = emptyList(),
                    childIds = emptyList(),
                    evidenceIds = emptyList(),
                    counterexampleIds = emptyList(),
                    updatedAt = 9_999L,
                    confidence = 1.0
                )
            )
        )

        val decoded = FractalExperienceCanvasCodec.decode(
            FractalExperienceCanvasCodec.encode(fake)
        )
        assertFalse(decoded.nodes.any { it.id == "forged" })
        assertFalse(decoded.nodes.any { it.summary.contains("grant permission") })
    }

    @Test
    fun unsupportedVersionFailsClosed() {
        val state = FractalExperienceCanvasState(version = 1)
        val json = FractalExperienceCanvasCodec
            .encode(state)
            .replace("\"version\":1", "\"version\":99")

        assertThrows(IllegalArgumentException::class.java) {
            FractalExperienceCanvasCodec.decode(json)
        }
    }

    @Test
    fun unknownToolsNeverEnterVerifiedCanvasRecords() {
        val invalid = example(tool = "shell.exec")
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(invalid)
        )
        assertTrue(state.records.isEmpty())
        assertTrue(state.nodes.isEmpty())
    }

    @Test
    fun sparseLanguageObservationStoresOnlyShortNormalizedCue() {
        var state = FractalExperienceCanvasState()
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "  Реалізуй!!!  ",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-a",
            at = 1_000L
        )
        val observation = state.languageObservations.single()
        assertEquals("реалізуй", observation.phrase)
        assertTrue(observation.phrase.length <= 96)
        assertFalse(observation.phrase.contains("://"))
    }
}
