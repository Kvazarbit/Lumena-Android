package com.lumena.android.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FractalExperienceCanvasPolicyTest {
    private fun example(
        id: String,
        kind: CoordinatorExampleKind,
        task: String,
        tools: List<String>,
        outcomes: List<Boolean>,
        scope: String = "scope-a",
        model: String = "model-a",
        at: Long = 1_000L,
        targets: List<String>? = null
    ) = CoordinatorExecutionExample(
        id = id,
        kind = kind,
        sourceSessionHash = task,
        tools = tools,
        targets = targets ?: tools.mapIndexed { index, tool -> "target=$tool-$index" },
        evidenceIds = listOf("evidence-$id"),
        updatedAt = at,
        surprise = 0.8,
        text = "fixture $id",
        contributorModelIds = listOf(model),
        scopeHash = scope,
        outcomes = outcomes
    )

    @Test
    fun verifiedMutationThenTestBuildsTransferredBestHierarchy() {
        val examples = listOf(
            example(
                id = "a",
                kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                task = "task-a",
                tools = listOf("file.patch", "python.tests"),
                outcomes = listOf(true, true),
                model = "gemma",
                at = 1_000L
            ),
            example(
                id = "b",
                kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                task = "task-b",
                tools = listOf("file.patch", "python.tests"),
                outcomes = listOf(true, true),
                model = "glm",
                at = 2_000L
            )
        )

        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            examples
        )

        assertTrue(state.nodes.any { it.level == FractalExperienceLevel.EPISODE })
        assertTrue(state.nodes.any { it.level == FractalExperienceLevel.PATTERN })
        assertTrue(state.nodes.any { it.level == FractalExperienceLevel.STRATEGY })
        val meta = state.nodes.single {
            it.level == FractalExperienceLevel.META_RULE &&
                it.key == "meta:MUTATE_THEN_VERIFY"
        }
        assertEquals(FractalExperiencePeak.BEST, meta.peak)
        assertEquals(FractalExperienceStage.TRANSFERRED_SHADOW, meta.stage)
        assertEquals(2, meta.distinctTasks)
        assertEquals(setOf("gemma", "glm"), meta.contributorModelIds.toSet())
        assertEquals(2, meta.supportCount)
        assertEquals(0, meta.failureCount)
        assertTrue(meta.childIds.isNotEmpty())
        assertTrue(meta.evidenceIds.contains("evidence-a"))
        assertTrue(meta.evidenceIds.contains("evidence-b"))
    }

    @Test
    fun repeatedFailedRecoveryBuildsTransferredWorstPeak() {
        val examples = listOf(
            example(
                id = "f1",
                kind = CoordinatorExampleKind.FAILED_RECOVERY,
                task = "task-f1",
                tools = listOf("python.run", "python.run"),
                outcomes = listOf(false, false),
                at = 1_000L
            ),
            example(
                id = "f2",
                kind = CoordinatorExampleKind.FAILED_RECOVERY,
                task = "task-f2",
                tools = listOf("python.run", "python.run"),
                outcomes = listOf(false, false),
                at = 2_000L
            )
        )
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            examples
        )
        val meta = state.nodes.single {
            it.level == FractalExperienceLevel.META_RULE &&
                it.key == "meta:AVOID_BLIND_REPEAT_AFTER_FAILURE"
        }
        assertEquals(FractalExperiencePeak.WORST, meta.peak)
        assertEquals(FractalExperienceStage.TRANSFERRED_SHADOW, meta.stage)
        assertEquals(0, meta.supportCount)
        assertEquals(2, meta.failureCount)
    }

    @Test
    fun contradictoryEvidenceStaysContestedAndNeverTransfers() {
        val sameShape = listOf(false, true)
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(
                example(
                    id = "recovered",
                    kind = CoordinatorExampleKind.RECOVERY,
                    task = "task-r",
                    tools = listOf("python.run", "python.run"),
                    outcomes = sameShape
                ),
                example(
                    id = "failed-label",
                    kind = CoordinatorExampleKind.FAILED_RECOVERY,
                    task = "task-f",
                    tools = listOf("python.run", "python.run"),
                    outcomes = sameShape,
                    at = 2_000L
                )
            )
        )

        val pattern = state.nodes.single {
            it.level == FractalExperienceLevel.PATTERN &&
                it.key.contains("python.run:fail>python.run:ok")
        }
        assertEquals(FractalExperiencePeak.CONTESTED, pattern.peak)
        assertEquals(FractalExperienceStage.SHADOW, pattern.stage)
        assertEquals(1, pattern.supportCount)
        assertEquals(1, pattern.failureCount)
        assertEquals(2, pattern.counterexampleIds.size)
    }

    @Test
    fun canonicalLanguageIntentSeparatesCodeContinuationFromResearchApply() {
        assertEquals(
            "CONTINUE_CODE",
            FractalLanguageIntentPolicy.canonicalIntent(
                codeContinued = true,
                researchFollowUpKind =
                    com.lumena.android.agent.core.ResearchFollowUpKind.APPLY,
                resolvedGoal = "Онови aquarium.html"
            )
        )
        assertEquals(
            "RESEARCH_APPLY",
            FractalLanguageIntentPolicy.canonicalIntent(
                codeContinued = false,
                researchFollowUpKind =
                    com.lumena.android.agent.core.ResearchFollowUpKind.APPLY,
                resolvedGoal = "реалізуй це"
            )
        )
        assertEquals(
            "CODE_WORK",
            FractalLanguageIntentPolicy.canonicalIntent(
                codeContinued = false,
                researchFollowUpKind =
                    com.lumena.android.agent.core.ResearchFollowUpKind.NONE,
                resolvedGoal = "Створи Python script"
            )
        )
    }

    @Test
    fun languageCueMovesUnknownToTransferredShadowAndConflictBackToContested() {
        var state = FractalExperienceCanvasState()
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "реалізуй",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-1",
            at = 1_000L
        )
        var cue = FractalExperienceCanvasPolicy.languageCues(state).single()
        assertEquals(FractalExperiencePeak.UNKNOWN, cue.peak)
        assertEquals(FractalExperienceStage.SHADOW, cue.stage)

        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "Реалізуй!",
            canonicalIntent = "CONTINUE_IMPLEMENTATION",
            sourceTaskId = "task-2",
            at = 2_000L
        )
        cue = FractalExperienceCanvasPolicy.languageCues(state).single()
        assertEquals(FractalExperiencePeak.BEST, cue.peak)
        assertEquals(FractalExperienceStage.TRANSFERRED_SHADOW, cue.stage)
        assertEquals(2, cue.distinctTasks)

        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            phrase = "реалізуй",
            canonicalIntent = "APPLY_RESEARCH",
            sourceTaskId = "task-3",
            at = 3_000L
        )
        cue = FractalExperienceCanvasPolicy.languageCues(state).single()
        assertEquals(FractalExperiencePeak.CONTESTED, cue.peak)
        assertEquals(FractalExperienceStage.SHADOW, cue.stage)
        assertEquals(setOf("APPLY_RESEARCH", "CONTINUE_IMPLEMENTATION"), cue.competingIntents.toSet())
    }

    @Test
    fun longOrUrlLanguageIsNotStoredAsVocabularyCue() {
        var state = FractalExperienceCanvasState()
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            "https://example.com implement this",
            "CONTINUE_IMPLEMENTATION",
            "task-1",
            1_000L
        )
        state = FractalExperienceCanvasPolicy.observeLanguage(
            state,
            "x".repeat(FractalExperienceCanvasPolicy.MAX_SHORT_CUE_CHARS + 1),
            "CONTINUE_IMPLEMENTATION",
            "task-2",
            2_000L
        )
        assertTrue(state.languageObservations.isEmpty())
    }

    @Test
    fun socialRetrievalCombinesVerifiedModelsForSameScopedTarget() {
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(
                example(
                    id = "social-a",
                    kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                    task = "task-a",
                    tools = listOf("file.patch", "python.tests"),
                    outcomes = listOf(true, true),
                    model = "ollama:gemma",
                    targets = listOf(
                        "path=aquarium.html",
                        "cwd=aquarium"
                    ),
                    at = 1_000L
                ),
                example(
                    id = "social-b",
                    kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                    task = "task-b",
                    tools = listOf("file.patch", "python.tests"),
                    outcomes = listOf(true, true),
                    model = "ollama:glm",
                    targets = listOf(
                        "path=aquarium.html",
                        "cwd=aquarium"
                    ),
                    at = 2_000L
                )
            )
        )

        val relevant = FractalExperienceCanvasPolicy.relevant(
            state = state,
            query = "update aquarium.html",
            scopeHash = "scope-a",
            limit = 8
        )
        assertTrue(relevant.isNotEmpty())
        val social = relevant.first {
            it.contributorModelIds.toSet() ==
                setOf("ollama:gemma", "ollama:glm")
        }
        assertEquals(FractalExperiencePeak.BEST, social.peak)
        assertEquals(
            FractalExperienceStage.TRANSFERRED_SHADOW,
            social.stage
        )
        val prompt =
            FractalExperienceCanvasPolicy.formatForPrompt(social)
        assertTrue(prompt.contains("models=2"))
        assertTrue(prompt.contains("aquarium.html"))
        assertTrue(prompt.contains("advisory only"))
    }

    @Test
    fun scopeFilterPreventsCrossProjectRetrievalLeak() {
        val state = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            listOf(
                example(
                    id = "a",
                    kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                    task = "task-a",
                    tools = listOf("file.patch", "python.tests"),
                    outcomes = listOf(true, true),
                    scope = "scope-a"
                ),
                example(
                    id = "b",
                    kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                    task = "task-b",
                    tools = listOf("web.search", "web.read"),
                    outcomes = listOf(true, true),
                    scope = "scope-b"
                )
            )
        )

        val scoped = FractalExperienceCanvasPolicy.relevant(
            state = state,
            query = "",
            scopeHash = "scope-a",
            limit = 20
        )
        assertTrue(scoped.isNotEmpty())
        assertTrue(scoped.all { it.scopeHash == "scope-a" })
        assertFalse(scoped.any { it.scopeHash == "scope-b" })
    }

    @Test
    fun ingestIsIdempotentAndBounded() {
        val many = (0 until 600).map { index ->
            example(
                id = "id-$index",
                kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
                task = "task-$index",
                tools = listOf("workspace.list", "file.read"),
                outcomes = listOf(true, true),
                at = 1_000L + index
            )
        }
        val once = FractalExperienceCanvasPolicy.ingest(
            FractalExperienceCanvasState(),
            many
        )
        val twice = FractalExperienceCanvasPolicy.ingest(
            once,
            many.takeLast(50)
        )
        assertTrue(once.records.size <= FractalExperienceCanvasPolicy.MAX_RECORDS)
        assertTrue(once.nodes.size <= FractalExperienceCanvasPolicy.MAX_NODES)
        assertEquals(once.records.map { it.id }, twice.records.map { it.id })
        assertEquals(once.nodes.map { it.id }, twice.nodes.map { it.id })
    }
}
