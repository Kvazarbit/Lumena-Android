package com.lumena.android.settings

import com.lumena.android.agent.core.*
import org.junit.Assert.*
import org.junit.Test

class ProjectConversationPolicyTest {
    @Test fun switchingRetainsUnknownEffectsAndDropsPendingApprovalWithoutReplay() {
        val call = AgentDecision.ToolCall("file.write", mapOf("path" to "one/main.py"))
        val task = TaskState("t", "one", "Write", status = TaskStatus.EXECUTING,
            kernel = ContextKernel.before(ContextKernelState(), call), projectRoot = "one")
        val session = LocalSessionSnapshot(task = task, pending = PersistedPendingTool("file.write", call.args), workspaceProjectId = "one")
        val restored = ProjectConversationPolicy.restore(session)
        assertEquals("one", restored.workspaceProjectId)
        assertNull(restored.pending)
        assertEquals(TaskStatus.PARTIAL, restored.task!!.status)
        assertEquals(task.kernel.inFlight, restored.task.kernel.inFlight)
        assertFalse(restored.task.canContinue)
    }

    @Test fun optionalHistoryCannotEvictTheLastConversationOfAnOldProject() {
        val old = HistoryBranch("old-project", "Old", "Work", "Main", createdAt = 1, updatedAt = 1,
            session = LocalSessionSnapshot(workspaceProjectId = "one", chat = listOf(PersistedChatMessage("user", "old private goal"))))
        val recent = (1..60).map { n -> HistoryBranch("b$n", "General", "Work", "Main", createdAt = n.toLong(), updatedAt = n.toLong(), session = LocalSessionSnapshot()) }
        val retained = ProjectConversationPolicy.retain(listOf(old) + recent, "b60", 48)
        assertEquals(48, retained.size)
        assertTrue(retained.contains(old))
        assertTrue(retained.any { it.id == "b60" })
        assertEquals("old private goal", retained.first { it.id == "old-project" }.session.chat.single().text)
    }
}
