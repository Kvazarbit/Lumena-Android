package com.lumena.android.settings

import com.lumena.android.agent.core.*
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class CognitiveExperienceFeedbackTest {
    private fun episode(task: String, ok: Boolean, family: String = "web.search"): CoordinatorExecutionExample {
        val state = (0..1).fold(CoordinatorEpisodeState()) { state, i ->
            CoordinatorExperiencePolicy.record(state, CoordinatorEpisodeEvent(
                id = "$task:$i", sessionId = "project", taskId = task, tool = family,
                target = "same", ok = i == 1 && ok, experienceId = "$task:ev$i",
                at = i + 1L, surprise = 0.5, modelId = "model", scopeHash = "project-hash"
            ))
        }
        return state.learnedExamples.single()
    }

    private fun event() = FailureEvent(
        source = FailureSource.TOOL, failureClass = FailureClass.DEPENDENCY_EXHAUSTED,
        retryable = false, effectClass = EffectClass.READ_ONLY, dependency = "web.search",
        evidence = "test", actionFamily = "web.search", attempt = 1, outcomeUnknown = false
    )
    private fun rank(examples: List<CoordinatorExecutionExample>) = ReflexExperienceRanker.rank(
        event(), RecoveryState(1, 1, 2, 2), examples
    )

    @Test fun failuresLowerTrustAndNeverAuthorizeNewOptions() {
        val positive = (1..8).map { episode("positive-$it", true) }
        val negative = (1..8).map { episode("negative-$it", false) }
        val before = requireNotNull(rank(positive).choice)
        val after = requireNotNull(rank(positive + negative).choice)
        assertTrue(after.confidence < before.confidence)
        assertEquals(before.scores.map { it.option }, after.scores.map { it.option })
        assertNull(rank(negative).choice)
    }

    @Test fun failureThenSuccessInSameTaskDoesNotEraseCounterexampleVote() {
        val fail = episode("same-task", false)
        val success = episode("same-task", true)
        assertNull(rank(listOf(fail, success)).choice)
    }

    @Test fun contradictoryExperienceContestsPreviouslyLearnedConstitution() {
        var genome = ConstitutionGenomeState()
        for (i in 1..3) {
            genome = ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
                genome, TaskState(id = "task-$i", projectId = "project", goal = "research"),
                listOf(episode("task-$i", true))
            )
        }
        assertEquals(ConstitutionRuleStatus.LEARNED, genome.rules.single().status)
        genome = ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
            genome, TaskState(id = "failed-task", projectId = "project", goal = "research"),
            listOf(episode("failed-task", false))
        )
        assertEquals(2, genome.rules.size)
        assertTrue(genome.rules.all { it.status == ConstitutionRuleStatus.CONTESTED })
        assertTrue(genome.rules.all { it.authority == ConstitutionAuthority.ADVISORY })
        assertTrue(ConstitutionGenomePolicy.effectiveRules(genome, ConstitutionScope(ConstitutionScopeKind.PROJECT, "project")).isEmpty())
    }

    @Test fun unknownOutcomeIsABarrierNotNegativeTraining() {
        val events = listOf(false, false, true).mapIndexed { i, ok -> CoordinatorEpisodeEvent(
            id = "e$i", sessionId = "s", taskId = "t", tool = "web.search", target = "x",
            ok = ok, experienceId = "ev$i", at = i + 1L, surprise = 0.5, outcomeKnown = i != 1
        ) }
        val state = events.fold(CoordinatorEpisodeState(), CoordinatorExperiencePolicy::record)
        assertTrue(state.learnedExamples.isEmpty())
    }

    @Test fun scopeAndModelAreFilteredBeforeRetrieval() {
        val own = episode("own", false)
        val foreign = episode("other", true).copy(scopeHash = "other-project")
        val otherModel = episode("other-model", true).copy(contributorModelIds = listOf("other"))
        val legacy = episode("legacy", true).copy(scopeHash = null)
        val state = CoordinatorEpisodeState(learnedExamples = listOf(own, foreign, otherModel, legacy))
        assertEquals(listOf(own), CoordinatorExperiencePolicy.scoped(state, "project-hash", "model").learnedExamples)
    }

    @Test fun negativeMemorySurvivesJsonRestartAndCompaction() {
        val original = CoordinatorEpisodeState(learnedExamples = listOf(episode("restart", false)))
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build().adapter(CoordinatorEpisodeState::class.java)
        val restored = requireNotNull(adapter.fromJson(adapter.toJson(original)))
        assertEquals(original, restored)
        assertEquals(CoordinatorExampleKind.FAILED_RECOVERY, CoordinatorExperiencePolicy.examples(restored, "", 4).single().kind)
        val old = requireNotNull(adapter.fromJson("""{"version":2,"events":[],"learnedExamples":[]}"""))
        assertTrue(old.events.isEmpty())
    }

    @Test fun realFailureTracesGrowReplayCorpusAcrossDomains() {
        val examples = listOf("web.search", "python.tests", "file.read").mapIndexed { i, family -> episode("domain-$i", false, family) }
        val corpus = CognitiveRegressionSuite.corpus(examples)
        val report = CognitiveRegressionSuite.replay(corpus)
        assertEquals(3, report.cases)
        assertEquals(3, report.passed)
        assertEquals(3, report.distinctTasks)
        assertTrue(report.failedCaseIds.isEmpty())
        assertTrue(corpus.all { it.targetKeys.none { target -> target == "same" } })
        assertEquals(corpus, CognitiveRegressionSuite.corpus(examples))
        val task = examples.first().sourceSessionHash
        assertEquals(CognitiveRegressionSuite.holdout(task), CognitiveRegressionSuite.holdout(task))
    }

    @Test fun legacyOrIncompleteTraceCannotFabricateBenchmark() {
        val example = episode("legacy", false)
        assertTrue(CognitiveRegressionSuite.corpus(listOf(example.copy(outcomes = emptyList()))).isEmpty())
        assertTrue(CognitiveRegressionSuite.corpus(listOf(example.copy(evidenceIds = emptyList()))).isEmpty())
        assertTrue(CognitiveRegressionSuite.corpus(listOf(episode("success", true))).isEmpty())
    }
    @Test fun sevenStepEpisodeCannotBeSilentlyTruncatedDuringExport() {
        val events = (0..6).map { i -> CoordinatorEpisodeEvent(
            id = "long-$i", sessionId = "s", taskId = "t",
            tool = if (i == 0 || i == 6) "web.search" else "web.read",
            target = if (i == 0 || i == 6) "query" else "page-$i",
            ok = i != 0, experienceId = "long-ev-$i", at = i + 1L, surprise = 0.5
        ) }
        val state = events.fold(CoordinatorEpisodeState(), CoordinatorExperiencePolicy::record)
        assertTrue(state.learnedExamples.all { it.tools.size <= 6 })
        assertTrue(state.learnedExamples.none { it.kind == CoordinatorExampleKind.RECOVERY })
    }

}
