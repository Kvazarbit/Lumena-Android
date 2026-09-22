package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReflexKernelTest {
    private fun event(
        source: FailureSource = FailureSource.TOOL,
        klass: FailureClass = FailureClass.STATE_DRIFT,
        retryable: Boolean? = true,
        effect: EffectClass = EffectClass.READ_ONLY,
        dependency: String? = "fixture",
        actionFamily: String? = "file.read",
        attempt: Int = 1,
        outcomeUnknown: Boolean = false
    ) = FailureEvent(
        source = source,
        failureClass = klass,
        retryable = retryable,
        effectClass = effect,
        dependency = dependency,
        evidence = "fixture",
        actionFamily = actionFamily,
        attempt = attempt,
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

    @Test
    fun unknownEffectMutationAllowsStopOnly() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.UNKNOWN_EFFECT,
                retryable = false,
                effect = EffectClass.MUTATING_OR_EXECUTABLE,
                actionFamily = "file.write",
                outcomeUnknown = true
            ),
            state()
        )

        assertEquals(ReflexOption.STOP, candidates.constitutionalAnchor)
        assertEquals(setOf(ReflexOption.STOP), candidates.allowed)
    }

    @Test
    fun policyDenialAllowsStopOnly() {
        val candidates = ReflexKernel.candidates(
            event(
                source = FailureSource.POLICY,
                klass = FailureClass.POLICY_DENIED,
                retryable = false,
                effect = EffectClass.MUTATING_OR_EXECUTABLE
            ),
            state()
        )

        assertEquals(setOf(ReflexOption.STOP), candidates.allowed)
    }

    @Test
    fun dependencyExhaustionAllowsVariantAndOnlyConservativeFallbacks() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.DEPENDENCY_EXHAUSTED,
                retryable = false,
                effect = EffectClass.READ_ONLY,
                dependency = "web.search",
                actionFamily = "web.search"
            ),
            state(familyFailures = 1, semanticSpent = 1)
        )

        assertEquals(ReflexOption.RETRY_VARIANT, candidates.constitutionalAnchor)
        assertTrue(ReflexOption.RETRY_VARIANT in candidates.allowed)
        assertTrue(ReflexOption.TRY_ALTERNATIVE in candidates.allowed)
        assertTrue(ReflexOption.ASK_PLANNER in candidates.allowed)
        assertTrue(ReflexOption.DEGRADE_PARTIAL in candidates.allowed)
        assertTrue(ReflexOption.STOP in candidates.allowed)
    }

    @Test
    fun stateDriftCannotBeEscalatedIntoRetryVariant() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.STATE_DRIFT,
                actionFamily = "file.read"
            ),
            state()
        )

        assertEquals(ReflexOption.TRY_ALTERNATIVE, candidates.constitutionalAnchor)
        assertFalse(ReflexOption.RETRY_VARIANT in candidates.allowed)
        assertTrue(ReflexOption.ASK_PLANNER in candidates.allowed)
    }

    @Test
    fun exhaustedSemanticBudgetAllowsOnlyPartialOrStop() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.DEPENDENCY_EXHAUSTED,
                actionFamily = "web.search"
            ),
            state(
                familyFailures = 1,
                semanticSpent = 2,
                maxFamily = 2,
                maxSemantic = 2
            )
        )

        assertEquals(
            ReflexOption.DEGRADE_PARTIAL,
            candidates.constitutionalAnchor
        )
        assertEquals(
            setOf(
                ReflexOption.DEGRADE_PARTIAL,
                ReflexOption.STOP
            ),
            candidates.allowed
        )
    }

    @Test
    fun rankRejectsOptionOutsideConstitutionalCandidateSet() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.STATE_DRIFT,
                actionFamily = "file.read"
            ),
            state()
        )

        val failed = runCatching {
            ReflexKernel.rank(
                candidates = candidates,
                scores = listOf(
                    ReflexChoiceScore(
                        ReflexOption.RETRY_VARIANT,
                        0.99
                    )
                ),
                confidence = 0.99,
                evidenceCount = 12
            )
        }

        assertTrue(failed.isFailure)
    }

    @Test
    fun rankTieBreakIsDeterministic() {
        val candidates = ReflexCandidateSet(
            allowed = linkedSetOf(
                ReflexOption.TRY_ALTERNATIVE,
                ReflexOption.ASK_PLANNER,
                ReflexOption.STOP
            ),
            constitutionalAnchor = ReflexOption.TRY_ALTERNATIVE,
            reason = "fixture"
        )
        val choice = ReflexKernel.rank(
            candidates = candidates,
            scores = listOf(
                ReflexChoiceScore(
                    ReflexOption.ASK_PLANNER,
                    0.8
                ),
                ReflexChoiceScore(
                    ReflexOption.TRY_ALTERNATIVE,
                    0.8
                )
            ),
            confidence = 0.8,
            evidenceCount = 4
        )

        assertEquals(
            ReflexOption.TRY_ALTERNATIVE,
            choice.best()
        )
    }

    @Test
    fun reflexRequiresEvidenceUnlessConstitutionAlreadyHardStops() {
        val candidates = ReflexKernel.candidates(
            event(
                klass = FailureClass.STATE_DRIFT
            ),
            state()
        )

        val noEvidence = ReflexKernel.shouldUseReflex(
            candidates = candidates,
            confidence = 0.99,
            threshold = 0.80,
            evidenceCount = 0
        )
        assertFalse(noEvidence.value)

        val withEvidence = ReflexKernel.shouldUseReflex(
            candidates = candidates,
            confidence = 0.90,
            threshold = 0.80,
            evidenceCount = 5
        )
        assertTrue(withEvidence.value)

        val hardStop = ReflexKernel.shouldUseReflex(
            candidates = ReflexKernel.candidates(
                event(
                    klass = FailureClass.UNKNOWN_EFFECT,
                    retryable = false,
                    effect = EffectClass.MUTATING_OR_EXECUTABLE,
                    outcomeUnknown = true
                ),
                state()
            ),
            confidence = 0.0,
            threshold = 0.95,
            evidenceCount = 0
        )
        assertTrue(hardStop.value)
    }

    @Test
    fun typedPrimitivesRejectOutOfRangeValues() {
        assertTrue(
            runCatching {
                ReflexBool(
                    value = true,
                    confidence = 1.1
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                ReflexScore(
                    value = -0.1,
                    confidence = 0.5
                )
            }.isFailure
        )
        assertTrue(
            runCatching {
                ReflexChoiceScore(
                    ReflexOption.STOP,
                    1.5
                )
            }.isFailure
        )
    }

    @Test
    fun constitutionAnchorIsAlwaysInCandidateSetForEveryFailureClass() {
        FailureClass.entries.forEach { klass ->
            val observed = event(
                klass = klass,
                retryable = if (
                    klass in setOf(
                        FailureClass.AUTH_OR_CONFIG,
                        FailureClass.MODEL_RUNTIME
                    )
                ) false else true,
                effect = if (
                    klass == FailureClass.UNKNOWN_EFFECT
                ) EffectClass.MUTATING_OR_EXECUTABLE
                else EffectClass.READ_ONLY,
                outcomeUnknown =
                    klass == FailureClass.UNKNOWN_EFFECT
            )

            val candidates = ReflexKernel.candidates(
                observed,
                state(
                    familyFailures = 1,
                    semanticSpent = 1
                )
            )

            assertTrue(
                "Anchor missing for $klass",
                candidates.constitutionalAnchor in
                    candidates.allowed
            )
        }
    }
}
