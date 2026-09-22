package com.lumena.android.settings

import com.lumena.android.agent.core.EffectClass
import com.lumena.android.agent.core.FailureClass
import com.lumena.android.agent.core.FailureEvent
import com.lumena.android.agent.core.FailureSource
import com.lumena.android.agent.core.RecoveryState
import com.lumena.android.agent.core.ReflexOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexExperienceRankerTest {
    private fun event(
        klass: FailureClass = FailureClass.DEPENDENCY_EXHAUSTED,
        family: String = "web.search",
        outcomeUnknown: Boolean = false,
        effect: EffectClass = EffectClass.READ_ONLY
    ) = FailureEvent(
        source = FailureSource.TOOL,
        failureClass = klass,
        retryable = false,
        effectClass = effect,
        dependency = family,
        evidence = "fixture",
        actionFamily = family,
        attempt = 1,
        outcomeUnknown = outcomeUnknown
    )

    private fun state(
        familyFailures: Int = 1,
        semanticSpent: Int = 1,
        maxFamily: Int = 2,
        maxSemantic: Int = 2
    ) = RecoveryState(
        familyFailures = familyFailures,
        semanticRecoverySpent = semanticSpent,
        maxFamilyFailures = maxFamily,
        maxSemanticRecoveries = maxSemantic
    )

    private fun recovery(
        id: String,
        tools: List<String>,
        evidence: List<String> = listOf("e-$id")
    ) = CoordinatorExecutionExample(
        id = id,
        kind = CoordinatorExampleKind.RECOVERY,
        sourceSessionHash = "abcdef1234567890",
        tools = tools,
        targets = tools.map { "query=fixture" },
        evidenceIds = evidence,
        updatedAt = 100,
        surprise = 1.0,
        text = "fixture"
    )

    @Test
    fun directVerifiedRetrySupportsRetryVariant() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = listOf(
                recovery(
                    "r1",
                    listOf("web.search", "web.search")
                )
            )
        )

        val choice = requireNotNull(recommendation.choice)
        assertEquals(ReflexOption.RETRY_VARIANT, choice.best())
        assertEquals(1, choice.evidenceCount)
        assertTrue(choice.confidence < 0.8)
        assertFalse(recommendation.calibrated)
    }

    @Test
    fun intermediateVerifiedStepsSupportTryAlternative() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = listOf(
                recovery(
                    "r1",
                    listOf(
                        "web.search",
                        "web.read",
                        "web.search"
                    )
                )
            )
        )

        assertEquals(
            ReflexOption.TRY_ALTERNATIVE,
            requireNotNull(recommendation.choice).best()
        )
    }

    @Test
    fun eightConsistentExamplesIncreaseEvidenceStrengthButRemainUncalibrated() {
        val examples = (1..8).map { index ->
            recovery(
                "r$index",
                listOf(
                    "web.search",
                    "web.read",
                    "web.search"
                )
            )
        }

        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = examples
        )

        val choice = requireNotNull(recommendation.choice)
        assertEquals(ReflexOption.TRY_ALTERNATIVE, choice.best())
        assertEquals(8, choice.evidenceCount)
        assertEquals(1.0, choice.confidence, 0.0001)
        assertFalse(recommendation.calibrated)
    }

    @Test
    fun conflictingRecoveryPatternsReduceStrength() {
        val examples = listOf(
            recovery(
                "retry",
                listOf("web.search", "web.search")
            ),
            recovery(
                "alternative",
                listOf(
                    "web.search",
                    "web.read",
                    "web.search"
                )
            )
        )

        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = examples
        )

        val choice = requireNotNull(recommendation.choice)
        assertEquals(2, choice.evidenceCount)
        assertTrue(choice.confidence < 0.2)
    }

    @Test
    fun evidenceForDifferentActionFamilyIsIgnored() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = listOf(
                recovery(
                    "file",
                    listOf("file.read", "file.read")
                )
            )
        )

        assertNull(recommendation.choice)
        assertEquals(0, recommendation.evidence.evidenceCount)
    }

    @Test
    fun exampleWithoutGenomeEvidenceCannotInfluenceReflex() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = listOf(
                recovery(
                    "unverified",
                    listOf("web.search", "web.search"),
                    evidence = emptyList()
                )
            )
        )

        assertNull(recommendation.choice)
    }

    @Test
    fun constitutionCanRejectRetryEvidenceWithoutBeingOverridden() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(
                klass = FailureClass.STATE_DRIFT,
                family = "file.read"
            ),
            state = state(),
            examples = listOf(
                recovery(
                    "r1",
                    listOf("file.read", "file.read")
                )
            )
        )

        assertNull(recommendation.choice)
        assertEquals(1, recommendation.evidence.evidenceCount)
    }

    @Test
    fun unknownEffectHardStopIgnoresPositiveRecoveryHistory() {
        val recommendation = ReflexExperienceRanker.rank(
            event = event(
                klass = FailureClass.UNKNOWN_EFFECT,
                family = "file.write",
                outcomeUnknown = true,
                effect = EffectClass.MUTATING_OR_EXECUTABLE
            ),
            state = state(),
            examples = listOf(
                recovery(
                    "dangerous",
                    listOf("file.write", "file.write")
                )
            )
        )

        val choice = requireNotNull(recommendation.choice)
        assertEquals(ReflexOption.STOP, choice.best())
        assertEquals(0, choice.evidenceCount)
    }

    @Test
    fun successfulSequenceIsNotMisusedAsRecoveryEvidence() {
        val sequence = CoordinatorExecutionExample(
            id = "sequence",
            kind = CoordinatorExampleKind.VERIFIED_SEQUENCE,
            sourceSessionHash = "abcdef1234567890",
            tools = listOf("web.search", "web.read"),
            targets = listOf("query=x", "url=x"),
            evidenceIds = listOf("e1", "e2"),
            updatedAt = 100,
            surprise = 0.9,
            text = "fixture"
        )

        val recommendation = ReflexExperienceRanker.rank(
            event = event(),
            state = state(),
            examples = listOf(sequence)
        )

        assertNull(recommendation.choice)
    }
}
