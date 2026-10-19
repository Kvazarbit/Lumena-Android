package com.lumena.android.settings

import com.lumena.android.agent.core.*
import org.junit.Assert.*
import org.junit.Test

class ProjectContinuationPolicyTest {
    private val p = WorkspaceProject("p", "Demo", "demo", "Maintain the aquarium", updatedAt = 1)
    @Test fun newOwnerTaskDoesNotAdoptAnUnrelatedOpenObligation() {
        val old = p.copy(tasks = listOf(ProjectWorkRecord("old", "Old unrelated work", TaskStatus.PARTIAL, "m", at = 1)))
        val fresh = TaskState("new", "p", "New explicit owner task", projectRoot = "demo")
        val card = ProjectContinuationPolicy.render(old, fresh)
        assertTrue(card.contains("New explicit owner task"))
        assertFalse(card.contains("Old unrelated work"))
        assertTrue(ProjectContinuationPolicy.render(old, fresh.copy(projectObligationId = "old")).contains("Old unrelated work"))
    }
    @Test fun returningToAnOldGoalStillCreatesANewOwnerEpoch() {
        val a = ProjectWorkspaceState(projects = listOf(p))
        val b = ProjectWorkspacePolicy.advanceGoalEpochs(a, a.copy(projects = listOf(p.copy(goal = "Different goal"))))
        val again = ProjectWorkspacePolicy.advanceGoalEpochs(b, a)
        assertEquals(2L, b.projects.single().goalEpoch)
        assertEquals(3L, again.projects.single().goalEpoch)
        assertNotEquals(com.lumena.android.collaboration.CollaborationProtocol.goalRevision(p),
            com.lumena.android.collaboration.CollaborationProtocol.goalRevision(again.projects.single()))
        assertEquals(3L, ProjectWorkspacePolicy.advanceGoalEpochs(again,
            again.copy(projects = again.projects.map { it.copy(updatedAt = 99) })).projects.single().goalEpoch)
    }
    @Test fun causalDecisionAndRejectedAlternativeSurviveModelChangeAndCodecRestart() {
        val decision = ProjectDecisionRecord("d", "aquarium state", "Use SQLite", "Transactions preserve state", listOf("Global mutable counters"), at = 2)
        val initial = p.copy(decisions = listOf(decision))
        val restored = ProjectWorkspaceCodec.decode(ProjectWorkspaceCodec.encode(ProjectWorkspaceState(projects = listOf(initial)))).projects.single()
        val task = TaskState("new-epoch", "p", "Continue aquarium", projectRoot = "demo")
        val card = ProjectContinuationPolicy.render(restored, task)
        for (s in listOf("Maintain the aquarium", "Use SQLite", "Transactions preserve state", "Global mutable counters", "NEXT:")) assertTrue(s, card.contains(s))
        assertTrue(card.length <= ProjectContinuationPolicy.MAX_CHARS)
        assertFalse(restored.allowRemoteContext)
    }
    @Test fun resumedUnknownObligationRemainsUnknownWithoutFreshToolEvidence() {
        val call = AgentDecision.ToolCall("file.write", mapOf("path" to "demo/a.py"))
        val first = TaskState("old", "p", "Write aquarium", projectRoot = "demo", status = TaskStatus.FAILED,
            kernel = ContextKernel.before(ContextKernelState(), call))
        val interrupted = ProjectWorkspacePolicy.record(p, first, "model-a", 2)
        val next = TaskState("new", "p", "Continue", projectRoot = "demo", projectObligationId = "old")
        val resumed = ProjectWorkspacePolicy.record(interrupted, next, "model-b", 3)
        assertTrue(resumed.tasks.single().unknownEffect)
        assertTrue(ProjectContinuationPolicy.render(resumed, next).contains("inspect UNKNOWN effects"))
        assertTrue(resumed.tasks.single().evidence.isEmpty())
    }
}
