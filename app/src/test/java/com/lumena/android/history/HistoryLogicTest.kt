package com.lumena.android.history

import com.lumena.android.agent.core.*
import com.lumena.android.agent.local.*
import com.lumena.android.ollama.*
import org.junit.Assert.*
import org.junit.Test

class HistoryLogicTest {
    private fun source(): Conversation {
        val task = TaskState("task-1", null, "Original goal", TaskStatus.WAITING_CONFIRMATION)
        val gate = ToolGate.plan(PlannerDecision(ToolRequest("file.write", mapOf("path" to "a.txt", "content" to "pending")), "write"))
        val control = AgentControlState(task, plan = listOf("write"), toolUsed = true, identicalToolCalls = 2)
        return Conversation(ConversationMeta("parent", "topic", "Parent", model = "local"), ConversationPayload(
            chat = listOf(ChatEntry("user", "first", "u1"), ChatEntry("assistant", "first result", "a1"), ChatEntry("user", "future", "u2")),
            history = listOf(OllamaMessage("user", "first"), OllamaMessage("assistant", "first result"), OllamaMessage("user", "future")),
            control = control, pending = PendingWorkflowTool(gate, emptyList(), control), draft = "do not copy",
            tasks = listOf(TaskMark("done", "first", TaskStatus.DONE, "a1", chatEnd = 2, historyEnd = 2),
                TaskMark("task-1", "future", TaskStatus.WAITING_CONFIRMATION, "u2"))
        ))
    }
    @Test fun forkCopiesOnlyCheckpointPrefixAndNeverAuthority() {
        val source = source()
        val branch = HistoryLogic.fork(source, "done", "branch")
        assertEquals("parent", branch.meta.parentId)
        assertEquals("topic", branch.meta.topicId)
        assertEquals(2, branch.payload.history.size)
        assertFalse(branch.payload.history.any { it.content == "future" })
        assertNull(branch.payload.pending)
        assertNull(branch.payload.control)
        assertEquals("", branch.payload.draft)
        assertTrue(branch.payload.tasks.all { it.inherited })
        assertEquals(1, branch.payload.tasks.size)
        assertEquals(3, source.payload.history.size)
        assertNotNull(source.payload.pending)
    }
    @Test(expected = IllegalArgumentException::class) fun branchCannotOverwriteParentId() {
        HistoryLogic.fork(source(), "done", "parent")
    }
    @Test(expected = IllegalStateException::class) fun cannotBranchPendingTask() {
        HistoryLogic.fork(source(), "task-1")
    }
    @Test fun treeSearchKeepsAncestorsAndCorrectTopic() {
        val root = ConversationMeta("r", "t", "root", updatedAt = 1)
        val branch = ConversationMeta("b", "t", "Python parser", parentId = "r", updatedAt = 2)
        val unrelated = ConversationMeta("u", "other", "Python")
        val result = HistoryLogic.tree(HistoryCatalog(conversations = listOf(root, branch, unrelated)), "t", "parser")
        assertEquals(listOf("r", "b"), result.map { it.meta.id })
        assertEquals(listOf(0, 1), result.map { it.depth })
    }
    @Test fun collapseHidesDescendantsWithoutChangingCatalog() {
        val c = HistoryCatalog(conversations = listOf(ConversationMeta("r", "t"), ConversationMeta("c", "t", parentId = "r")))
        assertEquals(listOf("r"), HistoryLogic.tree(c, "t", collapsed = setOf("r")).map { it.meta.id })
        assertEquals(2, c.conversations.size)
    }
    @Test fun executionAfterProcessDeathIsUncertainNotAutoResumed() {
        val source = source().payload
        val executing = source.copy(pending = null, control = source.control!!.copy(task = source.control.task.copy(status = TaskStatus.EXECUTING)))
        val recovered = HistoryLogic.interrupted(executing)
        assertTrue(recovered.executionUncertain)
        assertEquals(TaskStatus.FAILED, recovered.control!!.task.status)
        assertNull(recovered.pending)
        assertNotNull(recovered.recoveryNotice)
    }
    @Test fun unapprovedPendingCallRemainsPending() {
        assertEquals(source().payload, HistoryLogic.interrupted(source().payload))
    }
}
