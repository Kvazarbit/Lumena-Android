package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalTaskTrailTest {
    private fun episode(
        i: Int,
        model: String,
        family: String,
        success: Boolean,
        status: String = if (success) "DONE" else "PARTIAL",
        tokens: Long = 1_000
    ) = FractalTaskEpisode(
        id = "e$i", modelId = model, family = family, status = status, success = success,
        toolSteps = 2, modelCalls = 3, tokens = tokens, at = i + 1L
    )

    @Test fun everyModelLeavesOneTraceAndDuplicatesAreIgnored() {
        var state = FractalExperienceCanvasState()
        state = FractalTaskTrailPolicy.record(state, episode(1, "ollama:gemma", "CODE_WORK", true))
        state = FractalTaskTrailPolicy.record(state, episode(1, "ollama:gemma", "CODE_WORK", true))
        state = FractalTaskTrailPolicy.record(state, episode(2, "embedded:qwen", "CODE_WORK", false))
        assertEquals(2, state.taskEpisodes.size)
    }

    @Test fun hierarchyGoesFromModelSliceToFamilyToEverything() {
        var state = FractalExperienceCanvasState()
        var i = 0
        repeat(30) { state = FractalTaskTrailPolicy.record(state, episode(i++, "ollama:strong", "CODE_WORK", it % 10 < 9)) }
        repeat(30) { state = FractalTaskTrailPolicy.record(state, episode(i++, "ollama:weak", "CODE_WORK", it % 10 < 3)) }
        repeat(5) { state = FractalTaskTrailPolicy.record(state, episode(i++, "ollama:weak", "PUBLIC_WEB", true)) }

        val summaries = FractalTaskTrailPolicy.summaries(state)
        val meta = summaries.single { it.level == FractalTrailLevel.META_RULE }
        assertEquals(65, meta.tasks)

        val code = summaries.single { it.level == FractalTrailLevel.STRATEGY && it.family == "CODE_WORK" }
        assertEquals(0.6, code.successRate, 1e-9)

        val strong = summaries.single { it.modelId == "ollama:strong" }
        val weak = summaries.single { it.modelId == "ollama:weak" && it.family == "CODE_WORK" }
        assertEquals(FractalExperiencePeak.BEST, strong.peak)
        assertEquals(FractalExperiencePeak.WORST, weak.peak)

        // Too few tasks for a peak.
        val web = summaries.single { it.family == "PUBLIC_WEB" && it.modelId == "ollama:weak" }
        assertEquals(FractalExperiencePeak.UNKNOWN, web.peak)
    }

    @Test fun unresolvedTasksAreCountedButNotScored() {
        var state = FractalExperienceCanvasState()
        state = FractalTaskTrailPolicy.record(state, episode(1, "m", "GENERAL", true))
        state = FractalTaskTrailPolicy.record(state, episode(2, "m", "GENERAL", false, status = "UNRESOLVED", tokens = 0))
        val meta = FractalTaskTrailPolicy.summaries(state).first()
        assertEquals(2, meta.tasks)
        assertEquals(1, meta.resolved)
        assertEquals(1.0, meta.successRate, 1e-9)
    }

    @Test fun missingCostIsNotAveragedAsZero() {
        val state = FractalTaskTrailPolicy.record(
            FractalExperienceCanvasState(),
            episode(1, "m", "GENERAL", true, tokens = 0).copy(toolSteps = 0, modelCalls = 0)
        )
        val meta = FractalTaskTrailPolicy.summaries(state).first()
        assertNull(meta.meanTokens)
        assertNull(meta.meanSteps)
    }

    @Test fun trailSurvivesCanvasCodecRoundTrip() {
        val state = FractalTaskTrailPolicy.record(
            FractalExperienceCanvasState(),
            episode(1, "ollama:gemma", "CODE_WORK", true).copy(exposedLayers = listOf("fractal"), withheldLayers = listOf("memory"))
        )
        val restored = FractalExperienceCanvasCodec.decode(FractalExperienceCanvasCodec.encode(state))
        assertEquals(state.taskEpisodes, restored.taskEpisodes)

        val legacy = FractalExperienceCanvasCodec.decode(
            FractalExperienceCanvasCodec.encode(FractalExperienceCanvasState())
                .replace(",\"taskEpisodes\":[]", "")
        )
        assertTrue(legacy.taskEpisodes.isEmpty())
    }

    @Test fun wilsonIntervalIsSane() {
        val (low, high) = FractalTaskTrailPolicy.wilson(9, 10)
        assertTrue(low in 0.55..0.62 && high in 0.97..1.0)
        assertEquals(0.0 to 1.0, FractalTaskTrailPolicy.wilson(0, 0))
    }
}
