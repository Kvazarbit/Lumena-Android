package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bounded adversarial search over failure classifications and retry budgets.
 * The attacker varies the observation; the defender is the real policy.
 * This borrows bounded search and counterexample retention from game testing,
 * not AlphaGo's neural network or Monte Carlo tree search.
 */
class RecoveryAdversarialSearchTest {
    private fun event(
        source: FailureSource,
        klass: FailureClass,
        unknown: Boolean,
        retryable: Boolean?
    ) = FailureEvent(
        source = source,
        failureClass = klass,
        retryable = retryable,
        effectClass = EffectClass.MUTATING_OR_EXECUTABLE,
        dependency = if (source == FailureSource.MODEL_RUNTIME) "model" else "bridge",
        evidence = "adversarial fixture",
        actionFamily = "file.write",
        attempt = 1,
        outcomeUnknown = unknown
    )

    @Test
    fun unsafeOutcomesAndPolicyDenialsAlwaysStopAcrossTheSearchSpace() {
        var visited = 0
        for (source in FailureSource.entries) {
            for (klass in FailureClass.entries) {
                for (unknown in listOf(false, true)) {
                    for (retryable in listOf(false, true, null)) {
                        for (familyFailures in 0..2) {
                            for (semanticSpent in 0..2) {
                                val observed = event(source, klass, unknown, retryable)
                                val state = RecoveryState(familyFailures, semanticSpent, 2, 2)
                                val decision = ConstitutionKernel.decide(observed, state)
                                val candidates = ReflexKernel.candidates(observed, state)
                                val trace = "source=$source class=$klass unknown=$unknown " +
                                    "retryable=$retryable failures=$familyFailures semantic=$semanticSpent"
                                if (unknown || klass == FailureClass.UNKNOWN_EFFECT ||
                                    source == FailureSource.POLICY || klass == FailureClass.POLICY_DENIED
                                ) {
                                    assertTrue(trace, decision is RecoveryDecision.Stop)
                                    assertEquals(trace, setOf(ReflexOption.STOP), candidates.allowed)
                                }
                                assertTrue(trace, candidates.constitutionalAnchor in candidates.allowed)
                                visited++
                            }
                        }
                    }
                }
            }
        }
        assertTrue("Search must actually cover multiple boundaries", visited > 1_000)
    }

    @Test
    fun semanticBudgetBoundaryNeverOffersAnotherToolVariant() {
        for (klass in listOf(FailureClass.DEPENDENCY_EXHAUSTED, FailureClass.PROVIDER_CHALLENGE,
            FailureClass.STATE_DRIFT, FailureClass.INVALID_INPUT)) {
            val observed = event(FailureSource.TOOL, klass, false, true)
            val atLimit = RecoveryState(1, 2, 2, 2)
            assertTrue("class=$klass", ConstitutionKernel.decide(observed, atLimit) is RecoveryDecision.DegradePartial)
            assertEquals("class=$klass", setOf(ReflexOption.DEGRADE_PARTIAL, ReflexOption.STOP),
                ReflexKernel.candidates(observed, atLimit).allowed)
        }
    }

    @Test
    fun reflexCannotAcceptOneForbiddenMoveHiddenAmongAllowedMoves() {
        val observed = event(FailureSource.POLICY, FailureClass.POLICY_DENIED, false, false)
        val candidates = ReflexKernel.candidates(observed, RecoveryState(0, 0, 2, 2))
        var rejected = false
        try {
            ReflexKernel.rank(candidates, listOf(
                ReflexChoiceScore(ReflexOption.STOP, 0.1),
                ReflexChoiceScore(ReflexOption.RETRY_VARIANT, 0.9)
            ), confidence = 0.99, evidenceCount = 100)
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("A high confidence score cannot grant a forbidden action", rejected)
        assertFalse(ReflexOption.RETRY_VARIANT in candidates.allowed)
    }
}
