package com.lumena.android.agent.core

import org.junit.Test

/** Uses the real repository ContextKernel; no alternate execution ledger. */
class HistoricalExecutionFactsKernelTest {
    @Test fun kernelRecordedReadProjectsTheActualEvent() {
        val call = AgentDecision.ToolCall("file.read", mapOf("path" to "fixtures/aquarium.html"))
        val state = ContextKernel.record(ContextKernelState(), call, true, "verified fixture read")
        val facts = HistoricalExecutionFacts.capture("source-task", state)
        check(facts.ledgerState == HistoricalLedgerState.RECORDED)
        check(facts.facts.single().evidenceId == state.evidence.single().id)
        check(facts.facts.single().resultDigest == state.evidence.single().digest)
        check(facts.facts.single().phase == CognitivePhase.OBSERVE)
        check(facts.facts.single().outcome == HistoricalToolOutcome.SUCCESS)
    }

    @Test fun kernelFailedMutationRemainsFailureWithTaskLocalRevision() {
        val call = AgentDecision.ToolCall("file.write", mapOf("path" to "fixture.txt", "content" to "fixture"))
        val state = ContextKernel.record(ContextKernelState(), call, false, "partial effect possible")
        val facts = HistoricalExecutionFacts.capture("source-task", state)
        check(facts.facts.single().outcome == HistoricalToolOutcome.FAILURE)
        check(facts.facts.single().phase == CognitivePhase.ACT)
        check(facts.facts.single().sourceRevision == 1)
        check(ContextKernel.completionBlocker(state) != null)
    }

    @Test fun unknownKernelOutcomeRemainsBlockedAfterProjection() {
        val call = AgentDecision.ToolCall("file.write", mapOf("path" to "fixture.txt", "content" to "fixture"))
        val state = ContextKernel.before(ContextKernelState(), call)
        val facts = HistoricalExecutionFacts.capture("source-task", state)
        check(facts.unresolvedCallAtSnapshot)
        check(facts.facts.isEmpty())
        check(ContextKernel.completionBlocker(state) != null)
        check(runCatching { ContextKernel.before(state, call) }.isFailure)
    }

    @Test fun historicalReadDoesNotSatisfyANewGoalContract() {
        val call = AgentDecision.ToolCall("file.read", mapOf("path" to "fixtures/aquarium.html"))
        val oldKernel = ContextKernel.record(ContextKernelState(), call, true, "old contents")
        val facts = HistoricalExecutionFacts.capture("old-task", oldKernel)
        check(HistoricalExecutionFacts.render(facts).contains("outcome=SUCCESS"))
        val newContract = GoalContractPolicy.initial(
            intent = TaskIntent.FILE_INSPECTION,
            requiredTools = emptySet(), visualRequired = false,
            goal = "Прочитай поточний файл fixtures/aquarium.html"
        )
        check(!GoalContractPolicy.allMandatoryPassed(newContract))
        check(newContract.criteria.all { it.evidence.isEmpty() })
    }
}
