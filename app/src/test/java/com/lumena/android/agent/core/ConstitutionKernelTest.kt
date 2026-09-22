package com.lumena.android.agent.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConstitutionKernelTest {
    private fun event(
        source: FailureSource,
        klass: FailureClass,
        retryable: Boolean? = true,
        effect: EffectClass = EffectClass.NONE,
        attempt: Int = 1,
        dependency: String? = null,
        actionFamily: String? = null,
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
    fun protocolDeviationGetsBoundedCorrection() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.PROTOCOL,
                klass = FailureClass.INVALID_INPUT,
                attempt = 1,
                dependency = "model-protocol"
            ),
            state(familyFailures = 1)
        )

        assertTrue(decision is RecoveryDecision.TryAlternative)
    }

    @Test
    fun thirdProtocolDeviationStopsWhenBudgetIsTwo() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.PROTOCOL,
                klass = FailureClass.INVALID_INPUT,
                attempt = 3,
                dependency = "model-protocol"
            ),
            state(familyFailures = 3)
        )

        assertTrue(decision is RecoveryDecision.Stop)
    }

    @Test
    fun nonRetryableContextPressureStopsAtControllerLayer() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.CONTEXT,
                klass = FailureClass.CONTEXT_PRESSURE,
                retryable = false,
                attempt = 1,
                dependency = "model"
            ),
            state()
        )

        assertTrue(decision is RecoveryDecision.Stop)
    }

    @Test
    fun transientModelFailureRetriesWithinBudget() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TRANSPORT,
                klass = FailureClass.TRANSIENT_TRANSPORT,
                retryable = true,
                attempt = 1,
                dependency = "model"
            ),
            state()
        )

        assertTrue(decision is RecoveryDecision.TryAlternative)
    }

    @Test
    fun transientModelFailureStopsAfterBudget() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TRANSPORT,
                klass = FailureClass.TRANSIENT_TRANSPORT,
                retryable = true,
                attempt = 3,
                dependency = "model"
            ),
            state(familyFailures = 3)
        )

        assertTrue(decision is RecoveryDecision.Stop)
    }

    @Test
    fun firstDependencyExhaustionAllowsOneSemanticVariant() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TOOL,
                klass = FailureClass.DEPENDENCY_EXHAUSTED,
                retryable = false,
                effect = EffectClass.READ_ONLY,
                attempt = 1,
                dependency = "web.search",
                actionFamily = "web.search"
            ),
            state(familyFailures = 1, semanticSpent = 1)
        )

        assertTrue(decision is RecoveryDecision.RetryVariant)
    }

    @Test
    fun exhaustedToolBudgetDegradesPartial() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TOOL,
                klass = FailureClass.DEPENDENCY_EXHAUSTED,
                effect = EffectClass.READ_ONLY,
                attempt = 2,
                dependency = "web.search",
                actionFamily = "web.search"
            ),
            state(familyFailures = 2, semanticSpent = 2)
        )

        assertTrue(decision is RecoveryDecision.DegradePartial)
    }

    @Test
    fun unknownMutationOutcomeNeverReplays() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TOOL,
                klass = FailureClass.UNKNOWN_EFFECT,
                retryable = false,
                effect = EffectClass.MUTATING_OR_EXECUTABLE,
                dependency = "bridge",
                actionFamily = "file.write",
                outcomeUnknown = true
            ),
            state()
        )

        assertTrue(decision is RecoveryDecision.Stop)
    }

    @Test
    fun policyDenialNeverFallsThroughToAlternativeRoute() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.POLICY,
                klass = FailureClass.POLICY_DENIED,
                retryable = false,
                effect = EffectClass.MUTATING_OR_EXECUTABLE,
                dependency = "tool-registry",
                actionFamily = "file.write"
            ),
            state(familyFailures = 0, semanticSpent = 0)
        )

        assertTrue(decision is RecoveryDecision.Stop)
    }

    @Test
    fun stateDriftRequestsRediscovery() {
        val decision = ConstitutionKernel.decide(
            event(
                source = FailureSource.TOOL,
                klass = FailureClass.STATE_DRIFT,
                effect = EffectClass.READ_ONLY,
                dependency = "filesystem",
                actionFamily = "file.read"
            ),
            state(familyFailures = 1, semanticSpent = 1)
        )

        assertTrue(decision is RecoveryDecision.TryAlternative)
        decision as RecoveryDecision.TryAlternative
        assertTrue(decision.guidance.contains("Re-discover"))
    }

    @Test
    fun sameFailureAndStateProduceSameDecision() {
        val observed = event(
            source = FailureSource.TOOL,
            klass = FailureClass.PROVIDER_CHALLENGE,
            effect = EffectClass.READ_ONLY,
            dependency = "web.search",
            actionFamily = "web.search"
        )
        val recovery = state(familyFailures = 1, semanticSpent = 1)

        assertEquals(
            ConstitutionKernel.decide(observed, recovery),
            ConstitutionKernel.decide(observed, recovery)
        )
    }
}
