package com.lumena.android.agent.core

import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryPolicyTest {
    @Test
    fun searchVariantsShareOneActionFamily() {
        val a = RecoveryPolicy.actionFamily(
            AgentDecision.ToolCall("web.search", mapOf("query" to "BTC news"))
        )
        val b = RecoveryPolicy.actionFamily(
            AgentDecision.ToolCall("web.search", mapOf("query" to "latest Bitcoin headlines"))
        )
        assertTrue(a == "web.search")
        assertTrue(a == b)
    }

    @Test
    fun structuredSearchExhaustionIsClassifiedWithoutParsingUiText() {
        val klass = RecoveryPolicy.classifyToolFailure(
            tool = "web.search",
            errorCode = "SEARCH_EXHAUSTED",
            suppliedClass = "DEPENDENCY_EXHAUSTED",
            error = "localized message may change"
        )
        assertTrue(klass == FailureClass.DEPENDENCY_EXHAUSTED)
    }

    @Test
    fun firstSearchFailureAllowsOneSemanticVariant() {
        val decision = RecoveryPolicy.decide(
            RecoveryContext(
                failureClass = FailureClass.DEPENDENCY_EXHAUSTED,
                effectClass = EffectClass.READ_ONLY,
                actionFamily = "web.search",
                familyFailures = 1,
                semanticRecoverySpent = 1,
                maxFamilyFailures = 2,
                maxSemanticRecoveries = 2
            )
        )
        assertTrue(decision is RecoveryDecision.RetryVariant)
    }

    @Test
    fun exhaustedSemanticBudgetDegradesPartialRatherThanLoopsOrHardFails() {
        val decision = RecoveryPolicy.decide(
            RecoveryContext(
                failureClass = FailureClass.DEPENDENCY_EXHAUSTED,
                effectClass = EffectClass.READ_ONLY,
                actionFamily = "web.search",
                familyFailures = 2,
                semanticRecoverySpent = 2,
                maxFamilyFailures = 2,
                maxSemanticRecoveries = 2
            )
        )
        assertTrue(decision is RecoveryDecision.DegradePartial)
    }

    @Test
    fun unknownEffectNeverBlindlyReplays() {
        val decision = RecoveryPolicy.decide(
            RecoveryContext(
                failureClass = FailureClass.UNKNOWN_EFFECT,
                effectClass = EffectClass.MUTATING_OR_EXECUTABLE,
                actionFamily = "file.write",
                familyFailures = 1,
                semanticRecoverySpent = 1,
                maxFamilyFailures = 2,
                maxSemanticRecoveries = 2
            )
        )
        assertTrue(decision is RecoveryDecision.Stop)
    }
}
