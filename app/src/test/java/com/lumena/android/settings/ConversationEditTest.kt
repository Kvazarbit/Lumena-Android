package com.lumena.android.settings

import com.lumena.android.agent.core.ResearchThreadState
import com.lumena.android.agent.core.TaskState
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class ConversationEditTest {
    private val first = PersistedChatMessage("user", "same question", id = "first")
    private val answer = PersistedChatMessage("assistant", "earlier answer", id = "answer")
    private val second = PersistedChatMessage("user", "same question", id = "second")
    private val future = PersistedChatMessage("assistant", "future answer", id = "future")
    private fun snapshot() = LocalSessionSnapshot(
        chat = listOf(first, answer, second, future),
        history = listOf(PersistedHistoryMessage("user", "FUTURE_TOOL_RECEIPT")),
        task = TaskState("old-task", "project", "old goal"),
        pending = PersistedPendingTool("file.write"),
        researchGoal = "future research",
        researchThread = ResearchThreadState(rootGoal = "future research")
    )

    @Test fun repeatedQuestionBranchesByIdentityAndPreservesParent() {
        val parent = snapshot()
        val fork = ConversationEdit.fork(parent, second.id, " corrected ")
        assertEquals(listOf(first, answer), fork.chat)
        assertEquals("corrected", fork.inputDraft)
        assertEquals(listOf(first, answer, second, future), parent.chat)
        assertEquals(listOf("same question", "earlier answer"), fork.history.drop(1).map { it.content })
    }

    @Test fun branchDropsFutureEvidenceResearchTaskAndApproval() {
        val fork = ConversationEdit.fork(snapshot(), second.id, "corrected")
        assertNull(fork.task)
        assertNull(fork.pending)
        assertNull(fork.researchGoal)
        assertNull(fork.researchThread)
        assertFalse(fork.history.any { it.content.contains("FUTURE_TOOL_RECEIPT") || it.content == "future answer" })
        assertTrue(fork.history.first().content.contains("NOT been undone"))
    }

    @Test fun firstQuestionStartsWithoutLaterConversation() {
        val fork = ConversationEdit.fork(snapshot(), first.id, "new beginning")
        assertTrue(fork.chat.isEmpty())
        assertEquals(1, fork.history.size)
    }

    @Test fun assistantMissingAndEmptyEditsAreRejected() {
        for ((id, draft) in listOf(answer.id to "edit", "missing" to "edit", first.id to "  ")) {
            assertTrue(runCatching { ConversationEdit.fork(snapshot(), id, draft) }.isFailure)
        }
    }

    @Test fun legacyMessagesAcquireDistinctIdsThatSurvivePersistence() {
        val adapter = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
            .adapter(LocalSessionSnapshot::class.java)
        val old = adapter.fromJson("""{"chat":[{"role":"user","text":"same"},{"role":"user","text":"same"}]}""")!!
        assertNotEquals(old.chat[0].id, old.chat[1].id)
        assertEquals(old, adapter.fromJson(adapter.toJson(old)))
    }
}
