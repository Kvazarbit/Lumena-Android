package com.lumena.android.settings

import com.lumena.android.agent.core.ConstitutionGenomePolicy
import com.lumena.android.agent.core.ConstitutionGenomeState
import com.lumena.android.agent.core.ConstitutionRuleStatus
import com.lumena.android.agent.core.ConstitutionScope
import com.lumena.android.agent.core.ConstitutionScopeKind
import com.lumena.android.agent.core.ConstitutionStance
import com.lumena.android.agent.core.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2-C4: what counts as evidence, where experience applies, and how conflicts resolve. */
class ExperienceAddressGenomeTest {
    private val project = ConstitutionScope(ConstitutionScopeKind.PROJECT, "project")

    private fun example(id: String, ok: Boolean, failureClass: String?) = CoordinatorExecutionExample(
        id = id,
        kind = if (ok) CoordinatorExampleKind.RECOVERY else CoordinatorExampleKind.FAILED_RECOVERY,
        sourceSessionHash = "session-$id",
        tools = listOf("web.search", "web.search"),
        targets = listOf("query=x", "query=x"),
        evidenceIds = listOf("$id:failed-first", "$id:final"),
        updatedAt = 100,
        surprise = 0.5,
        text = "fixture",
        outcomes = listOf(false, ok),
        failureClasses = listOf(failureClass, null)
    )

    private fun ingest(state: ConstitutionGenomeState, task: String, ok: Boolean, failureClass: String?) =
        ConstitutionContributionPolicy.ingestVerifiedRecoveryExamples(
            state, TaskState(id = task, projectId = "project", goal = "research"),
            listOf(example(task, ok, failureClass))
        )

    @Test fun failureCauseSeparatesClaimsThatOnlyLookedContradictory() {
        var genome = ConstitutionGenomeState()
        // Retrying works after timeouts and fails when every provider is exhausted.
        for (i in 1..3) genome = ingest(genome, "timeout-$i", true, "TIMEOUT")
        for (i in 1..3) genome = ingest(genome, "exhausted-$i", false, "DEPENDENCY_EXHAUSTED")

        val keys = genome.rules.map { it.claimKey }.toSet()
        assertEquals(
            setOf(
                "recovery:web.search:TIMEOUT:direct-retry",
                "recovery:web.search:DEPENDENCY_EXHAUSTED:direct-retry"
            ),
            keys
        )
        assertTrue(genome.rules.none { it.status == ConstitutionRuleStatus.CONTESTED })
        val timeout = genome.rules.single { it.claimKey.contains("TIMEOUT") }
        assertEquals(ConstitutionStance.AFFIRM, timeout.stance)
        assertEquals(ConstitutionRuleStatus.LEARNED, timeout.status)
        assertTrue(timeout.statement.contains("(TIMEOUT)"))
    }

    @Test fun missingCauseIsAddressedAsUnknownNotGuessed() {
        val genome = ingest(ConstitutionGenomeState(), "t", true, null)
        assertEquals("recovery:web.search:UNKNOWN:direct-retry", genome.rules.single().claimKey)
    }

    @Test fun onlyTheDecisiveAttemptCountsAsEvidence() {
        val genome = ingest(ConstitutionGenomeState(), "t", true, "TIMEOUT")
        assertEquals(listOf("t:final"), genome.rules.single().evidenceRefs.map { it.id })
    }

    @Test fun clearMajorityAfterAContradictionStaysUsable() {
        var genome = ConstitutionGenomeState()
        for (i in 1..3) genome = ingest(genome, "ok-$i", true, "TIMEOUT")
        genome = ingest(genome, "bad-1", false, "TIMEOUT")
        // Small sample: still contested.
        assertTrue(genome.rules.all { it.status == ConstitutionRuleStatus.CONTESTED })

        for (i in 4..23) genome = ingest(genome, "ok-$i", true, "TIMEOUT")
        val affirm = genome.rules.single { it.stance == ConstitutionStance.AFFIRM }
        val reject = genome.rules.single { it.stance == ConstitutionStance.REJECT }
        assertEquals(ConstitutionRuleStatus.LEARNED, affirm.status)
        // The counterexample is kept as history, not erased.
        assertEquals(ConstitutionRuleStatus.CONTESTED, reject.status)
        assertTrue(ConstitutionGenomePolicy.effectiveRules(genome, project).any { it.id == affirm.id })
    }

    @Test fun evenlySplitExperienceRemainsContested() {
        var genome = ConstitutionGenomeState()
        for (i in 1..5) genome = ingest(genome, "ok-$i", true, "TIMEOUT")
        for (i in 1..5) genome = ingest(genome, "bad-$i", false, "TIMEOUT")
        assertTrue(genome.rules.all { it.status == ConstitutionRuleStatus.CONTESTED })
        assertTrue(ConstitutionGenomePolicy.effectiveRules(genome, project).none { it.claimKey.startsWith("recovery:") })
    }

    @Test fun clearlyFailingPatternTurnsIntoAWarning() {
        var genome = ConstitutionGenomeState()
        genome = ingest(genome, "ok-1", true, "DEPENDENCY_EXHAUSTED")
        for (i in 1..20) genome = ingest(genome, "bad-$i", false, "DEPENDENCY_EXHAUSTED")
        val reject = genome.rules.single { it.stance == ConstitutionStance.REJECT }
        val affirm = genome.rules.single { it.stance == ConstitutionStance.AFFIRM }
        assertEquals(ConstitutionRuleStatus.LEARNED, reject.status)
        assertEquals(ConstitutionRuleStatus.CONTESTED, affirm.status)
    }
}
